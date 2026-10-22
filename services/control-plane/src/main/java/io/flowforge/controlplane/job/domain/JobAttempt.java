package io.flowforge.controlplane.job.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobAttempt(
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
}
