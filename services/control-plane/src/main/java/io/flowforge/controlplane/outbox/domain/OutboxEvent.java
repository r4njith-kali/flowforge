package io.flowforge.controlplane.outbox.domain;

import io.flowforge.controlplane.job.domain.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final String JOB_CREATED = "JobCreated";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "claimed_by", length = 200)
    private String claimedBy;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    protected OutboxEvent() {
    }

    public static OutboxEvent jobCreated(Job job, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.eventType = JOB_CREATED;
        event.aggregateId = job.getId();
        event.schemaVersion = CURRENT_SCHEMA_VERSION;
        event.payload = new HashMap<>();
        event.payload.put("jobId", job.getId().toString());
        event.payload.put("jobType", job.getJobType());
        event.payload.put("status", job.getStatus().name());
        event.payload.put("payload", job.getPayload());
        event.payload.put("createdAt", job.getCreatedAt().toString());
        event.payload.put(
                "scheduledAt",
                job.getScheduledAt() == null ? null : job.getScheduledAt().toString());
        event.payload.put("maxAttempts", job.getMaxAttempts());
        event.payload.put("retryDelaySeconds", job.getRetryDelaySeconds());
        event.createdAt = now;
        event.nextAttemptAt = now;
        event.attemptCount = 0;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }
}
