package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.job.domain.JobAttempt;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A persisted worker execution attempt")
public record JobAttemptResponse(
        UUID id,
        int attemptNumber,
        String workerId,
        String status,
        Instant startedAt,
        Instant heartbeatAt,
        Instant leaseExpiresAt,
        Instant finishedAt,
        String failureType,
        Map<String, Object> error,
        Map<String, Object> result) {

    public static JobAttemptResponse from(JobAttempt attempt) {
        return new JobAttemptResponse(
                attempt.id(),
                attempt.attemptNumber(),
                attempt.workerId(),
                attempt.status(),
                attempt.startedAt(),
                attempt.heartbeatAt(),
                attempt.leaseExpiresAt(),
                attempt.finishedAt(),
                attempt.failureType(),
                attempt.error(),
                attempt.result());
    }
}
