package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Persisted job details")
public record JobResponse(
        UUID id,
        String jobType,
        Map<String, Object> payload,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelRequestedAt,
        int attemptCount,
        int maxAttempts,
        int retryDelaySeconds,
        long version) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getJobType(),
                job.getPayload(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getScheduledAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getCancelRequestedAt(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getRetryDelaySeconds(),
                job.getVersion());
    }
}
