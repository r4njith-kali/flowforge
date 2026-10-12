package io.flowforge.controlplane.outbox.persistence;

import io.flowforge.controlplane.outbox.domain.ClaimedOutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxStore {

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT event.id, event.aggregate_id
                FROM outbox_events event
                JOIN jobs job ON job.id = event.aggregate_id
                WHERE event.published_at IS NULL
                  AND event.terminal_failure_at IS NULL
                  AND event.cancelled_at IS NULL
                  AND event.next_attempt_at <= clock_timestamp()
                  AND event.attempt_count < ?
                  AND (event.claim_expires_at IS NULL
                       OR event.claim_expires_at <= clock_timestamp())
                  AND (
                      job.status IN ('PENDING', 'DISPATCHING')
                      OR (job.status = 'SCHEDULED' AND job.scheduled_at <= clock_timestamp())
                  )
                ORDER BY event.next_attempt_at, event.created_at, event.id
                FOR UPDATE OF event, job SKIP LOCKED
                LIMIT ?
            )
            UPDATE outbox_events event
            SET attempt_count = event.attempt_count + 1,
                claimed_by = ?,
                claim_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING event.id, event.aggregate_id, event.event_type, event.schema_version,
                      event.created_at, event.attempt_count, event.payload::text
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<ClaimedOutboxEvent> claimBatch(
            UUID publisherId,
            int batchSize,
            int maxAttempts,
            Duration claimLease) {
        return transactionTemplate.execute(status -> {
            terminalizeExpiredFinalClaims(maxAttempts);
            List<ClaimedOutboxEvent> claimed = jdbcTemplate.query(
                    CLAIM_SQL,
                    (resultSet, rowNumber) -> new ClaimedOutboxEvent(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getObject("aggregate_id", UUID.class),
                            resultSet.getString("event_type"),
                            resultSet.getInt("schema_version"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getInt("attempt_count"),
                            resultSet.getString("payload")),
                    maxAttempts,
                    batchSize,
                    publisherId.toString(),
                    claimLease.toMillis());
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'DISPATCHING',
                        updated_at = clock_timestamp(),
                        version = version + 1
                    WHERE id IN (
                        SELECT aggregate_id FROM outbox_events WHERE claimed_by = ?
                    )
                      AND status IN ('PENDING', 'SCHEDULED', 'DISPATCHING')
                      AND (status <> 'SCHEDULED' OR scheduled_at <= clock_timestamp())
                    """, publisherId.toString());
            return claimed;
        });
    }

    public boolean isClaimPublishable(UUID eventId, UUID publisherId) {
        Boolean publishable = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM outbox_events event
                    JOIN jobs job ON job.id = event.aggregate_id
                    WHERE event.id = ?
                      AND event.claimed_by = ?
                      AND event.claim_expires_at > clock_timestamp()
                      AND event.published_at IS NULL
                      AND event.terminal_failure_at IS NULL
                      AND event.cancelled_at IS NULL
                      AND job.status = 'DISPATCHING'
                )
                """, Boolean.class, eventId, publisherId.toString());
        return Boolean.TRUE.equals(publishable);
    }

    public boolean markPublished(UUID eventId, UUID publisherId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE outbox_events
                    SET published_at = clock_timestamp(),
                        claimed_by = NULL,
                        claim_expires_at = NULL,
                        last_error = NULL
                    WHERE id = ?
                      AND claimed_by = ?
                      AND published_at IS NULL
                      AND terminal_failure_at IS NULL
                      AND cancelled_at IS NULL
                    """, eventId, publisherId.toString());
            if (updated == 0) {
                return false;
            }
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'DISPATCHED',
                        updated_at = clock_timestamp(),
                        version = version + 1
                    WHERE id = (
                        SELECT aggregate_id FROM outbox_events WHERE id = ?
                    )
                      AND status = 'DISPATCHING'
                    """, eventId);
            return true;
        }));
    }

    public boolean markFailed(
            ClaimedOutboxEvent event,
            UUID publisherId,
            int maxAttempts,
            Duration retryDelay,
            String errorMessage) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean terminal = event.attemptCount() >= maxAttempts;
            int updated = jdbcTemplate.update("""
                    UPDATE outbox_events
                    SET terminal_failure_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                        next_attempt_at = CASE
                            WHEN ? THEN next_attempt_at
                            ELSE clock_timestamp() + (? * INTERVAL '1 millisecond')
                        END,
                        last_error = ?,
                        claimed_by = NULL,
                        claim_expires_at = NULL
                    WHERE id = ?
                      AND claimed_by = ?
                      AND published_at IS NULL
                      AND terminal_failure_at IS NULL
                      AND cancelled_at IS NULL
                    """,
                    terminal,
                    terminal,
                    retryDelay.toMillis(),
                    abbreviate(errorMessage),
                    event.id(),
                    publisherId.toString());
            if (updated == 0) {
                return false;
            }
            if (terminal) {
                jdbcTemplate.update("""
                        UPDATE jobs
                        SET status = 'FAILED',
                            completed_at = clock_timestamp(),
                            updated_at = clock_timestamp(),
                            version = version + 1
                        WHERE id = ? AND status = 'DISPATCHING'
                        """, event.aggregateId());
            } else {
                jdbcTemplate.update("""
                        UPDATE jobs
                        SET status = CASE
                                WHEN scheduled_at IS NOT NULL
                                     AND scheduled_at > clock_timestamp() THEN 'SCHEDULED'
                                ELSE 'PENDING'
                            END,
                            updated_at = clock_timestamp(),
                            version = version + 1
                        WHERE id = ? AND status = 'DISPATCHING'
                        """, event.aggregateId());
            }
            return true;
        }));
    }

    public long countPending() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM outbox_events
                WHERE published_at IS NULL
                  AND terminal_failure_at IS NULL
                  AND cancelled_at IS NULL
                """, Long.class);
        return count == null ? 0 : count;
    }

    private void terminalizeExpiredFinalClaims(int maxAttempts) {
        jdbcTemplate.update("""
                WITH exhausted AS (
                    UPDATE outbox_events
                    SET terminal_failure_at = clock_timestamp(),
                        last_error = 'Claim lease expired after final publication attempt',
                        claimed_by = NULL,
                        claim_expires_at = NULL
                    WHERE published_at IS NULL
                      AND terminal_failure_at IS NULL
                      AND cancelled_at IS NULL
                      AND claimed_by IS NOT NULL
                      AND claim_expires_at <= clock_timestamp()
                      AND attempt_count >= ?
                    RETURNING aggregate_id
                )
                UPDATE jobs
                SET status = 'FAILED',
                    completed_at = clock_timestamp(),
                    updated_at = clock_timestamp(),
                    version = version + 1
                WHERE id IN (SELECT aggregate_id FROM exhausted)
                  AND status = 'DISPATCHING'
                """, maxAttempts);
    }

    private static String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "Kafka publication failed";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
