package io.flowforge.controlplane.idempotency.service;

import io.flowforge.controlplane.idempotency.persistence.IdempotencyRecordRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class IdempotencyRetentionService {

    private final IdempotencyRecordRepository repository;
    private final Clock clock;

    public IdempotencyRetentionService(IdempotencyRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowforge.idempotency.cleanup-interval-ms:3600000}")
    @Transactional
    public void removeExpiredRecords() {
        repository.deleteExpired(clock.instant());
    }
}
