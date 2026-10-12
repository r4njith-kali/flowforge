package io.flowforge.controlplane.job.service;

import io.flowforge.controlplane.job.domain.InvalidJobStateException;
import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobNotFoundException;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.persistence.JobRepository;
import io.flowforge.controlplane.job.persistence.JobSpecifications;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public JobService(
            JobRepository jobRepository,
            Clock clock,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager) {
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    Job create(
            String jobType,
            Map<String, Object> payload,
            Instant scheduledAt,
            Integer maxAttempts,
            Integer retryDelaySeconds) {
        Job job = Job.create(
                jobType,
                payload,
                scheduledAt,
                maxAttempts == null ? Job.DEFAULT_MAX_ATTEMPTS : maxAttempts,
                retryDelaySeconds == null ? 0 : retryDelaySeconds,
                clock.instant());
        return jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public Page<Job> list(JobStatus status, String jobType, Pageable pageable) {
        return jobRepository.findAll(JobSpecifications.matching(status, jobType), pageable);
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional
    public Job cancel(UUID id) {
        Job job = jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() == JobStatus.CANCELLED) {
            return job;
        }
        if (job.getStatus() == JobStatus.RUNNING && job.getCancelRequestedAt() != null) {
            return job;
        }
        Instant now = clock.instant();
        var updatedStatus = jdbcTemplate.query("""
                UPDATE jobs
                SET status = CASE WHEN status = 'RUNNING' THEN 'RUNNING' ELSE 'CANCELLED' END,
                    cancel_requested_at = CASE
                        WHEN status = 'RUNNING' THEN COALESCE(cancel_requested_at, ?)
                        ELSE cancel_requested_at
                    END,
                    completed_at = CASE
                        WHEN status = 'RUNNING' THEN completed_at
                        ELSE ?
                    END,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND status IN ('PENDING', 'SCHEDULED', 'DISPATCHING', 'RUNNING')
                  AND (status <> 'RUNNING' OR cancel_requested_at IS NULL)
                RETURNING status
                """,
                (resultSet, rowNumber) -> resultSet.getString("status"),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                id);
        if (updatedStatus.isEmpty()) {
            entityManager.clear();
            Job latest = jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
            if (latest.getStatus() == JobStatus.CANCELLED) {
                return latest;
            }
            if (latest.getStatus() == JobStatus.RUNNING && latest.getCancelRequestedAt() != null) {
                return latest;
            }
            latest.cancel(now);
        }
        if ("CANCELLED".equals(updatedStatus.isEmpty() ? null : updatedStatus.getFirst())) {
            jdbcTemplate.update("""
                    UPDATE outbox_events
                    SET cancelled_at = ?, claimed_by = NULL, claim_expires_at = NULL
                    WHERE aggregate_id = ?
                      AND published_at IS NULL
                      AND terminal_failure_at IS NULL
                      AND cancelled_at IS NULL
                    """, Timestamp.from(now), id);
        }
        entityManager.clear();
        return jobRepository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }
}
