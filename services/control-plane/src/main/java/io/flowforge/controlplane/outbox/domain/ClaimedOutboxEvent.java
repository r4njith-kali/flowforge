package io.flowforge.controlplane.outbox.domain;

import java.time.Instant;
import java.util.UUID;

public record ClaimedOutboxEvent(
        UUID id,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        Instant createdAt,
        int attemptCount,
        String payloadJson) {
}
