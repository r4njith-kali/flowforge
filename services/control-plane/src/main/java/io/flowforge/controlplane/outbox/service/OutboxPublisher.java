package io.flowforge.controlplane.outbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.outbox.domain.ClaimedOutboxEvent;
import io.flowforge.controlplane.outbox.domain.JobEventEnvelope;
import io.flowforge.controlplane.outbox.persistence.OutboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxStore outboxStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final UUID publisherId = UUID.randomUUID();
    private final String topic;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimLease;
    private final Duration sendTimeout;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    public OutboxPublisher(
            OutboxStore outboxStore,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock,
            @Value("${flowforge.kafka.topics.job-dispatch}") String topic,
            @Value("${flowforge.kafka.publisher.batch-size}") int batchSize,
            @Value("${flowforge.kafka.publisher.max-attempts}") int maxAttempts,
            @Value("${flowforge.kafka.publisher.claim-lease}") Duration claimLease,
            @Value("${flowforge.kafka.publisher.send-timeout}") Duration sendTimeout,
            @Value("${flowforge.kafka.publisher.retry-base-delay}") Duration retryBaseDelay,
            @Value("${flowforge.kafka.publisher.retry-max-delay}") Duration retryMaxDelay) {
        this.outboxStore = outboxStore;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.topic = topic;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.claimLease = claimLease;
        this.sendTimeout = sendTimeout;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Outbox batch size and max attempts must be positive");
        }
        if (claimLease.toMillis() <= 0
                || claimLease.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException("Outbox claim lease must exceed the Kafka send timeout");
        }
        if (sendTimeout.toMillis() <= 0
                || retryBaseDelay.isNegative()
                || retryMaxDelay.isNegative()
                || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("Invalid Kafka timeout or outbox retry delay configuration");
        }
        meterRegistry.gauge("flowforge.outbox.pending", outboxStore, OutboxStore::countPending);
    }

    public int publishBatch() {
        var events = outboxStore.claimBatch(publisherId, batchSize, maxAttempts, claimLease);
        meterRegistry.counter("flowforge.outbox.claimed").increment(events.size());
        int published = 0;
        for (ClaimedOutboxEvent event : events) {
            if (!outboxStore.isClaimPublishable(event.id(), publisherId)) {
                continue;
            }
            try {
                kafkaTemplate.send(
                                topic,
                                event.aggregateId().toString(),
                                serialize(event))
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                recordFailure(event, exception);
                continue;
            }

            meterRegistry.counter("flowforge.outbox.kafka.acknowledged").increment();
            if (outboxStore.markPublished(event.id(), publisherId)) {
                published++;
                meterRegistry.counter("flowforge.outbox.published").increment();
                Duration latency = Duration.between(event.createdAt(), clock.instant());
                Timer.builder("flowforge.job.dispatch.latency")
                        .register(meterRegistry)
                        .record(latency.isNegative() ? Duration.ZERO : latency);
                LOGGER.atInfo()
                        .addKeyValue("eventId", event.id())
                        .addKeyValue("aggregateId", event.aggregateId())
                        .addKeyValue("topic", topic)
                        .addKeyValue("attempt", event.attemptCount())
                        .log("Published outbox event");
            } else {
                LOGGER.atWarn()
                        .addKeyValue("eventId", event.id())
                        .addKeyValue("aggregateId", event.aggregateId())
                        .log("Kafka acknowledged event after its outbox claim was lost");
            }
        }
        return published;
    }

    private String serialize(ClaimedOutboxEvent event) throws Exception {
        JsonNode payload = objectMapper.readTree(event.payloadJson());
        return objectMapper.writeValueAsString(new JobEventEnvelope(
                event.id(),
                event.eventType(),
                event.schemaVersion(),
                event.createdAt(),
                event.aggregateId(),
                payload));
    }

    private void recordFailure(ClaimedOutboxEvent event, Exception exception) {
        meterRegistry.counter("flowforge.outbox.publication.failures").increment();
        Duration delay = retryDelay(event.attemptCount());
        boolean updated = outboxStore.markFailed(
                event,
                publisherId,
                maxAttempts,
                delay,
                rootMessage(exception));
        if (event.attemptCount() >= maxAttempts && updated) {
            meterRegistry.counter("flowforge.outbox.terminal.failures").increment();
            LOGGER.atError()
                    .addKeyValue("eventId", event.id())
                    .addKeyValue("aggregateId", event.aggregateId())
                    .addKeyValue("attempt", event.attemptCount())
                    .setCause(exception)
                    .log("Outbox event exhausted publication attempts");
        } else {
            LOGGER.atWarn()
                    .addKeyValue("eventId", event.id())
                    .addKeyValue("aggregateId", event.aggregateId())
                    .addKeyValue("attempt", event.attemptCount())
                    .addKeyValue("retryDelayMs", delay.toMillis())
                    .setCause(exception)
                    .log("Outbox publication failed; retry scheduled");
        }
    }

    private Duration retryDelay(int attemptCount) {
        long baseMillis = retryBaseDelay.toMillis();
        long maxMillis = retryMaxDelay.toMillis();
        int shift = Math.min(Math.max(attemptCount - 1, 0), 62);
        long delayMillis = baseMillis > (maxMillis >> shift)
                ? maxMillis
                : Math.min(maxMillis, baseMillis << shift);
        return Duration.ofMillis(delayMillis);
    }

    private static String rootMessage(Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
