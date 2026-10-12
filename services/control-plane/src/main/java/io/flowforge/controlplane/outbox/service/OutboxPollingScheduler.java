package io.flowforge.controlplane.outbox.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "flowforge.kafka.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxPollingScheduler {

    private final OutboxPublisher publisher;

    public OutboxPollingScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${flowforge.kafka.publisher.poll-interval-ms:1000}")
    public void poll() {
        publisher.publishBatch();
    }
}
