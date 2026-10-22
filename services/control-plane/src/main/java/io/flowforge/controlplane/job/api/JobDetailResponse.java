package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Persisted job details, including its final execution outcome")
public record JobDetailResponse(
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
        Map<String, Object> result,
        Map<String, Object> error,
        int attemptCount,
        int maxAttempts,
        int retryDelaySeconds,
        long version) {

    public static JobDetailResponse from(Job job) {
        return new JobDetailResponse(
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
                job.getResult(),
                job.getError(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getRetryDelaySeconds(),
                job.getVersion());
    }
}
