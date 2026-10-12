package io.flowforge.worker.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobExecutionClaim(
        UUID jobId,
        String jobType,
        Map<String, Object> payload,
        int attemptNumber,
        int jobMaxAttempts,
        int retryDelaySeconds,
        long fencingToken,
        String workerId,
        Instant leaseExpiresAt) {
}
