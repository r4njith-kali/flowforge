package io.flowforge.controlplane.job.service;

import io.flowforge.controlplane.idempotency.domain.IdempotencyConflictException;
import io.flowforge.controlplane.idempotency.domain.IdempotencyRecord;
import io.flowforge.controlplane.idempotency.persistence.IdempotencyRecordRepository;
import io.flowforge.controlplane.job.api.CreateJobRequest;
import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.outbox.domain.OutboxEvent;
import io.flowforge.controlplane.outbox.persistence.OutboxEventRepository;
import io.flowforge.controlplane.security.JobOwner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class JobSubmissionService {

    public static final String OPERATION_NAME = "POST:/api/v1/jobs";
    private final TransactionTemplate transactionTemplate;
    private final JobService jobService;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final OutboxEventRepository outboxRepository;
    private final JobRequestFingerprint fingerprint;
    private final Clock clock;
    private final Duration idempotencyRetention;
    private final JdbcTemplate jdbcTemplate;

    public JobSubmissionService(
            PlatformTransactionManager transactionManager,
            JobService jobService,
            IdempotencyRecordRepository idempotencyRepository,
            OutboxEventRepository outboxRepository,
            JobRequestFingerprint fingerprint,
            Clock clock,
            JdbcTemplate jdbcTemplate,
            @Value("${flowforge.idempotency.retention:PT24H}") Duration idempotencyRetention) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jobService = jobService;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.fingerprint = fingerprint;
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyRetention = idempotencyRetention;
    }

    public JobSubmissionResult submit(
            CreateJobRequest request,
            String idempotencyKey,
            JobOwner owner) {
        String requestFingerprint = idempotencyKey == null ? null : fingerprint.calculate(request);
        try {
            return submitInTransaction(request, idempotencyKey, requestFingerprint, owner);
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey == null) {
                throw exception;
            }
            return resolveConcurrentSubmission(
                    request,
                    idempotencyKey,
                    requestFingerprint,
                    owner,
                    exception);
        }
    }

    private JobSubmissionResult submitInTransaction(
            CreateJobRequest request,
            String idempotencyKey,
            String requestFingerprint,
            JobOwner owner) {
        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            if (idempotencyKey != null) {
                jdbcTemplate.query(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        resultSet -> { },
                        OPERATION_NAME + ":" + owner.issuer() + ":" + owner.subject() + ":" + idempotencyKey);
                var existing = idempotencyRepository.findForUpdate(
                        OPERATION_NAME,
                        owner.issuer(),
                        owner.subject(),
                        idempotencyKey);
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    if (record.getExpiresAt().isAfter(now)) {
                        return replayOrConflict(record, requestFingerprint, owner);
                    }
                    idempotencyRepository.delete(record);
                    idempotencyRepository.flush();
                }
            }

            Job job = jobService.create(
                    request.jobType(),
                    request.payload(),
                    request.scheduledAt(),
                    request.maxAttempts(),
                    request.retryDelaySeconds(),
                    owner);
            outboxRepository.save(OutboxEvent.jobCreated(job, now));

            if (idempotencyKey != null) {
                idempotencyRepository.saveAndFlush(IdempotencyRecord.create(
                        OPERATION_NAME,
                        idempotencyKey,
                        owner,
                        requestFingerprint,
                        job.getId(),
                        now,
                        now.plus(idempotencyRetention)));
            }
            return new JobSubmissionResult(job, false);
        });
    }

    private JobSubmissionResult resolveConcurrentSubmission(
            CreateJobRequest request,
            String idempotencyKey,
            String requestFingerprint,
            JobOwner owner,
            DataIntegrityViolationException originalException) {
        var record = idempotencyRepository.findById(
                new IdempotencyRecord.Id(
                        OPERATION_NAME,
                        owner.issuer(),
                        owner.subject(),
                        idempotencyKey));
        if (record.isEmpty()) {
            throw originalException;
        }
        if (!record.get().getExpiresAt().isAfter(clock.instant())) {
            return submitInTransaction(request, idempotencyKey, requestFingerprint, owner);
        }
        return replayOrConflict(record.get(), requestFingerprint, owner);
    }

    private JobSubmissionResult replayOrConflict(
            IdempotencyRecord record,
            String requestFingerprint,
            JobOwner owner) {
        if (!record.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyConflictException();
        }
        return new JobSubmissionResult(jobService.get(record.getJobId(), owner, false), true);
    }
}
