package io.flowforge.controlplane.outbox.persistence;

import io.flowforge.controlplane.outbox.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
}
