package io.flowforge.worker.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.worker.domain.FailureKind;
import io.flowforge.worker.domain.JobExecutionClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobExecutionStore {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JobExecutionStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public Optional<JobExecutionClaim> claim(
            UUID jobId,
            String workerId,
            Duration lease,
            int workerMaxAttempts) {
        return transactionTemplate.execute(status ->
                claimLocked(jobId, workerId, lease, workerMaxAttempts));
    }

    public List<JobExecutionClaim> claimRecoverable(
            String workerId,
            int batchSize,
            Duration lease,
            int workerMaxAttempts) {
        return transactionTemplate.execute(status -> {
            List<UUID> jobIds = jdbcTemplate.query("""
                    SELECT id
                    FROM jobs
                    WHERE (
                        status = 'DISPATCHED'
                        AND next_attempt_at <= clock_timestamp()
                        AND (scheduled_at IS NULL OR scheduled_at <= clock_timestamp())
                    ) OR (
                        status = 'RUNNING'
                        AND lease_expires_at <= clock_timestamp()
                    )
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class), batchSize);
            List<JobExecutionClaim> claims = new ArrayList<>();
            for (UUID jobId : jobIds) {
                claimLocked(jobId, workerId, lease, workerMaxAttempts).ifPresent(claims::add);
            }
            return claims;
        });
    }

    private Optional<JobExecutionClaim> claimLocked(
            UUID jobId,
            String workerId,
            Duration lease,
            int workerMaxAttempts) {
        List<JobRow> rows = jdbcTemplate.query("""
                SELECT id, job_type, payload::text AS payload, status, attempt_count,
                       max_attempts, retry_delay_seconds, fencing_token,
                       cancel_requested_at IS NOT NULL AS cancel_requested
                FROM jobs
                WHERE id = ?
                  AND (
                      (status = 'DISPATCHED'
                       AND next_attempt_at <= clock_timestamp()
                       AND (scheduled_at IS NULL OR scheduled_at <= clock_timestamp()))
                      OR (status = 'RUNNING' AND lease_expires_at <= clock_timestamp())
                  )
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new JobRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("job_type"),
                resultSet.getString("payload"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getInt("retry_delay_seconds"),
                resultSet.getLong("fencing_token"),
                resultSet.getBoolean("cancel_requested")), jobId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        JobRow job = rows.getFirst();
        if ("RUNNING".equals(job.status())) {
            jdbcTemplate.update("""
                    UPDATE job_attempts
                    SET status = 'ABANDONED',
                        failure_type = 'WORKER_LOST',
                        finished_at = clock_timestamp(),
                        error = jsonb_build_object('message', 'Worker lease expired')
                    WHERE job_id = ? AND attempt_number = ? AND status = 'RUNNING'
                    """, job.id(), job.attemptCount());
        }
        if (job.cancelRequested()) {
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'CANCELLED',
                        completed_at = clock_timestamp(),
                        updated_at = clock_timestamp(),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        version = version + 1
                    WHERE id = ?
                    """, job.id());
            jdbcTemplate.update("""
                    UPDATE job_attempts
                    SET status = 'CANCELLED', finished_at = clock_timestamp()
                    WHERE job_id = ? AND attempt_number = ?
                    """, job.id(), job.attemptCount());
            return Optional.empty();
        }

        int maxAttempts = Math.min(job.maxAttempts(), workerMaxAttempts);
        if (job.attemptCount() >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'FAILED',
                        completed_at = clock_timestamp(),
                        updated_at = clock_timestamp(),
                        error = jsonb_build_object('message', 'Worker attempts exhausted after lease expiry'),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        version = version + 1
                    WHERE id = ?
                    """, job.id());
            return Optional.empty();
        }

        ClaimedRow claimed = jdbcTemplate.queryForObject("""
                UPDATE jobs
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                    fencing_token = fencing_token + 1,
                    started_at = COALESCE(started_at, clock_timestamp()),
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id = ?
                RETURNING id, attempt_count, fencing_token, lease_expires_at
                """, (resultSet, rowNumber) -> new ClaimedRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getLong("fencing_token"),
                resultSet.getTimestamp("lease_expires_at").toInstant()),
                workerId,
                lease.toMillis(),
                job.id());

        jdbcTemplate.update("""
                INSERT INTO job_attempts (
                    id, job_id, attempt_number, worker_id, fencing_token, status,
                    started_at, heartbeat_at, lease_expires_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', clock_timestamp(), clock_timestamp(), ?)
                """, UUID.randomUUID(), job.id(), claimed.attemptNumber(), workerId,
                claimed.fencingToken(), Timestamp.from(claimed.leaseExpiresAt()));

        return Optional.of(new JobExecutionClaim(
                job.id(),
                job.jobType(),
                parsePayload(job.payload()),
                claimed.attemptNumber(),
                job.maxAttempts(),
                job.retryDelaySeconds(),
                claimed.fencingToken(),
                workerId,
                claimed.leaseExpiresAt()));
    }

    public boolean heartbeat(String workerId, Duration lease) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            List<ClaimedRow> renewed = jdbcTemplate.query("""
                    UPDATE jobs
                    SET lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        updated_at = clock_timestamp(),
                        version = version + 1
                    WHERE lease_owner = ?
                      AND status = 'RUNNING'
                      AND lease_expires_at > clock_timestamp()
                    RETURNING id, attempt_count AS attempt_number, fencing_token, lease_expires_at
                    """, (resultSet, rowNumber) -> new ClaimedRow(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getInt("attempt_number"),
                    resultSet.getLong("fencing_token"),
                    resultSet.getTimestamp("lease_expires_at").toInstant()),
                    lease.toMillis(), workerId);
            for (ClaimedRow row : renewed) {
                jdbcTemplate.update("""
                        UPDATE job_attempts
                        SET heartbeat_at = clock_timestamp(), lease_expires_at = ?
                        WHERE job_id = ? AND worker_id = ? AND attempt_number = ?
                          AND fencing_token = ? AND status = 'RUNNING'
                        """, Timestamp.from(row.leaseExpiresAt()), row.jobId(), workerId,
                        row.attemptNumber(), row.fencingToken());
            }
            return !renewed.isEmpty();
        }));
    }

    public boolean cancellationRequested(JobExecutionClaim claim) {
        Boolean requested = jdbcTemplate.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM jobs
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_owner = ? AND fencing_token = ?
                      AND lease_expires_at > clock_timestamp()
                ) OR EXISTS (
                    SELECT 1 FROM jobs
                    WHERE id = ? AND cancel_requested_at IS NOT NULL
                )
                """, Boolean.class,
                claim.jobId(), claim.workerId(), claim.fencingToken(), claim.jobId());
        return Boolean.TRUE.equals(requested);
    }

    public CompletionResult complete(JobExecutionClaim claim, String resultJson) {
        return transactionTemplate.execute(status -> {
            List<String> updated = jdbcTemplate.query("""
                    UPDATE jobs
                    SET status = CASE WHEN cancel_requested_at IS NULL THEN 'COMPLETED' ELSE 'CANCELLED' END,
                        result = CASE WHEN cancel_requested_at IS NULL THEN ?::jsonb ELSE NULL END,
                        completed_at = clock_timestamp(),
                        updated_at = clock_timestamp(),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        version = version + 1
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_owner = ? AND fencing_token = ?
                      AND lease_expires_at > clock_timestamp()
                    RETURNING status
                    """, (resultSet, rowNumber) -> resultSet.getString("status"),
                    resultJson, claim.jobId(), claim.workerId(), claim.fencingToken());
            if (updated.isEmpty()) {
                return CompletionResult.LOST_OWNERSHIP;
            }
            boolean cancelled = "CANCELLED".equals(updated.getFirst());
            jdbcTemplate.update("""
                    UPDATE job_attempts
                    SET status = ?, result = CASE WHEN ? THEN NULL ELSE ?::jsonb END,
                        finished_at = clock_timestamp()
                    WHERE job_id = ? AND attempt_number = ? AND fencing_token = ? AND status = 'RUNNING'
                    """,
                    cancelled ? "CANCELLED" : "COMPLETED",
                    cancelled,
                    resultJson,
                    claim.jobId(),
                    claim.attemptNumber(),
                    claim.fencingToken());
            return cancelled ? CompletionResult.CANCELLED : CompletionResult.COMPLETED;
        });
    }

    public FailureResult fail(
            JobExecutionClaim claim,
            FailureKind failureKind,
            String errorJson,
            int workerMaxAttempts,
            Duration retryDelay) {
        return transactionTemplate.execute(status -> {
            List<Boolean> owned = jdbcTemplate.query("""
                    SELECT cancel_requested_at IS NOT NULL
                    FROM jobs
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_owner = ? AND fencing_token = ?
                      AND lease_expires_at > clock_timestamp()
                    FOR UPDATE
                    """, (resultSet, rowNumber) -> resultSet.getBoolean(1),
                    claim.jobId(), claim.workerId(), claim.fencingToken());
            if (owned.isEmpty()) {
                return FailureResult.LOST_OWNERSHIP;
            }
            boolean cancelled = owned.getFirst();
            int maxAttempts = Math.min(claim.jobMaxAttempts(), workerMaxAttempts);
            boolean retry = !cancelled
                    && failureKind == FailureKind.TRANSIENT
                    && claim.attemptNumber() < maxAttempts;
            String jobStatus = cancelled ? "CANCELLED" : retry ? "DISPATCHED" : "FAILED";
            String attemptStatus = cancelled ? "CANCELLED" : retry ? "RETRY_WAIT" : "FAILED";
            int jobUpdated = jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = ?,
                        error = CASE WHEN ? THEN error ELSE ?::jsonb END,
                        next_attempt_at = CASE WHEN ? THEN clock_timestamp() + (? * INTERVAL '1 millisecond')
                                               ELSE next_attempt_at END,
                        completed_at = CASE WHEN ? THEN completed_at ELSE clock_timestamp() END,
                        updated_at = clock_timestamp(),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        version = version + 1
                    WHERE id = ? AND status = 'RUNNING'
                      AND lease_owner = ? AND fencing_token = ?
                      AND lease_expires_at > clock_timestamp()
                    """,
                    jobStatus,
                    cancelled,
                    errorJson,
                    retry,
                    retryDelay.toMillis(),
                    retry,
                    claim.jobId(),
                    claim.workerId(),
                    claim.fencingToken());
            if (jobUpdated == 0) {
                return FailureResult.LOST_OWNERSHIP;
            }
            jdbcTemplate.update("""
                    UPDATE job_attempts
                    SET status = ?, failure_type = ?, error = ?::jsonb,
                        finished_at = clock_timestamp()
                    WHERE job_id = ? AND attempt_number = ? AND fencing_token = ? AND status = 'RUNNING'
                    """,
                    attemptStatus,
                    cancelled ? null : failureKind.name(),
                    errorJson,
                    claim.jobId(),
                    claim.attemptNumber(),
                    claim.fencingToken());
            return retry ? FailureResult.RETRY_SCHEDULED
                    : cancelled ? FailureResult.CANCELLED
                    : FailureResult.TERMINAL;
        });
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Persisted job payload is not valid JSON", exception);
        }
    }

    private record JobRow(
            UUID id,
            String jobType,
            String payload,
            String status,
            int attemptCount,
            int maxAttempts,
            int retryDelaySeconds,
            long fencingToken,
            boolean cancelRequested) {
    }

    private record ClaimedRow(
            UUID jobId,
            int attemptNumber,
            long fencingToken,
            java.time.Instant leaseExpiresAt) {
    }

    public enum FailureResult {
        RETRY_SCHEDULED,
        TERMINAL,
        CANCELLED,
        LOST_OWNERSHIP
    }

    public enum CompletionResult {
        COMPLETED,
        CANCELLED,
        LOST_OWNERSHIP
    }
}
