package io.flowforge.controlplane.job.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.flowforge.controlplane.security.JobOwner;

@Entity
@Table(name = "jobs")
public class Job {

    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    @Id
    private UUID id;

    @Column(name = "job_type", nullable = false, length = 200)
    private String jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error", columnDefinition = "jsonb")
    private Map<String, Object> error;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "retry_delay_seconds", nullable = false)
    private int retryDelaySeconds;

    @Column(name = "owner_issuer", length = 512)
    private String ownerIssuer;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Version
    @Column(nullable = false)
    private long version;

    protected Job() {
    }

    public static Job create(
            String jobType,
            Map<String, Object> payload,
            Instant scheduledAt,
            int maxAttempts,
            int retryDelaySeconds,
            Instant now) {
        return create(jobType, payload, scheduledAt, maxAttempts, retryDelaySeconds, now, null);
    }

    public static Job create(
            String jobType,
            Map<String, Object> payload,
            Instant scheduledAt,
            int maxAttempts,
            int retryDelaySeconds,
            Instant now,
            JobOwner owner) {
        Job job = new Job();
        job.id = UUID.randomUUID();
        job.jobType = jobType;
        job.payload = payload;
        job.status = scheduledAt != null && scheduledAt.isAfter(now)
                ? JobStatus.SCHEDULED
                : JobStatus.PENDING;
        job.createdAt = now;
        job.updatedAt = now;
        job.scheduledAt = scheduledAt;
        job.attemptCount = 0;
        job.maxAttempts = maxAttempts;
        job.retryDelaySeconds = retryDelaySeconds;
        if (owner != null) {
            job.ownerIssuer = owner.issuer();
            job.ownerSubject = owner.subject();
        }
        return job;
    }

    public void cancel(Instant now) {
        if (status != JobStatus.PENDING
                && status != JobStatus.SCHEDULED
                && status != JobStatus.DISPATCHING) {
            throw new InvalidJobStateException(
                    "Jobs can only be cancelled before dispatch is acknowledged");
        }
        status = JobStatus.CANCELLED;
        completedAt = now;
        updatedAt = now;
    }

    public void requestCancellation(Instant now) {
        if (status != JobStatus.RUNNING) {
            throw new InvalidJobStateException("Only RUNNING jobs can receive a cancellation request");
        }
        if (cancelRequestedAt == null) {
            cancelRequestedAt = now;
            updatedAt = now;
        }
    }

    public void start(Instant now) {
        if (status != JobStatus.PENDING) {
            throw new InvalidJobStateException("Only PENDING jobs can be started");
        }
        status = JobStatus.RUNNING;
        startedAt = now;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getJobType() {
        return jobType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public Map<String, Object> getError() {
        return error;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public long getVersion() {
        return version;
    }

    public String getOwnerIssuer() {
        return ownerIssuer;
    }

    public String getOwnerSubject() {
        return ownerSubject;
    }
}
