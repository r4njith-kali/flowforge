package io.flowforge.controlplane.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.job.api.CreateJobRequest;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import io.flowforge.controlplane.job.service.JobService;
import io.flowforge.controlplane.security.JobOwner;
import io.flowforge.controlplane.outbox.domain.ClaimedOutboxEvent;
import io.flowforge.controlplane.outbox.domain.JobEventEnvelope;
import io.flowforge.controlplane.outbox.persistence.OutboxStore;
import io.flowforge.controlplane.outbox.service.OutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "flowforge.kafka.publisher.enabled=false",
        "flowforge.kafka.publisher.claim-lease=PT30S"
})
@Testcontainers
class OutboxPublisherIntegrationTest {

    private static final String TOPIC = "flowforge.job-dispatch.v1";
    private static final JobOwner SYSTEM_OWNER = new JobOwner("https://issuer.test", "system");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobSubmissionService jobSubmissionService;

    @Autowired
    private JobService jobService;

    @Autowired
    private OutboxStore outboxStore;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM jobs");
    }

    @Test
    void publishesAcknowledgedEventWithVersionedEnvelopeAndJobKey() throws Exception {
        UUID jobId = createJob("email.send", null);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        ConsumerRecord<String, String> record = readRecordsForJob(jobId, 1).getFirst();
        JsonNode message = objectMapper.readTree(record.value());

        assertThat(record.key()).isEqualTo(jobId.toString());
        assertThat(message.path("eventType").asText()).isEqualTo("JobCreated");
        assertThat(message.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.path("eventId").asText()).isNotBlank();
        assertThat(message.path("aggregateId").asText()).isEqualTo(jobId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class,
                jobId)).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHED.name());
    }

    @Test
    void futureScheduledJobWaitsUntilItsTimeAndThenDispatches() throws Exception {
        UUID jobId = createJob("report.generate", Instant.now().plusSeconds(3600));

        assertThat(publisher.publishBatch()).isZero();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.SCHEDULED.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                jobId)).isZero();

        jdbcTemplate.update(
                "UPDATE jobs SET scheduled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                jobId);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(readRecordsForJob(jobId, 1)).hasSize(1);
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHED.name());
    }

    @Test
    void cancellationBeforeDispatchSuppressesOutboxPublication() throws Exception {
        UUID jobId = createJob("email.send", null);

        jobService.cancel(jobId, SYSTEM_OWNER, true);

        assertThat(publisher.publishBatch()).isZero();
        assertThat(readRecordsForJob(jobId, 1)).isEmpty();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.CANCELLED.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancelled_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class,
                jobId)).isTrue();
    }

    @Test
    void cancellationAfterClaimButBeforeKafkaSendSuppressesPublication() throws Exception {
        UUID jobId = createJob("email.send", null);
        UUID publisherId = UUID.randomUUID();
        var claimed = outboxStore.claimBatch(publisherId, 10, 8, Duration.ofSeconds(30));
        assertThat(claimed).hasSize(1);
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHING.name());

        jobService.cancel(jobId, SYSTEM_OWNER, true);

        assertThat(outboxStore.isClaimPublishable(claimed.getFirst().id(), publisherId)).isFalse();
        assertThat(publisher.publishBatch()).isZero();
        assertThat(readRecordsForJob(jobId, 0)).isEmpty();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.CANCELLED.name());
    }

    @Test
    void expiredClaimIsRecoveredAfterPublisherCrash() throws Exception {
        UUID jobId = createJob("email.send", null);
        UUID crashedPublisher = UUID.randomUUID();
        List<ClaimedOutboxEvent> claimed =
                outboxStore.claimBatch(crashedPublisher, 10, 8, Duration.ofSeconds(30));
        assertThat(claimed).hasSize(1);

        jdbcTemplate.update(
                "UPDATE outbox_events SET claim_expires_at = now() - interval '1 second' "
                        + "WHERE id = ?",
                claimed.getFirst().id());

        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(readRecordsForJob(jobId, 1)).hasSize(1);
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHED.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                jobId)).isEqualTo(2);
    }

    @Test
    void duplicateDeliveryAfterAckBeforeDatabaseCommitKeepsStableEventId() throws Exception {
        UUID jobId = createJob("email.send", null);
        UUID crashedPublisher = UUID.randomUUID();
        ClaimedOutboxEvent claimed = outboxStore
                .claimBatch(crashedPublisher, 10, 8, Duration.ofSeconds(30))
                .getFirst();
        JsonNode payload = objectMapper.readTree(claimed.payloadJson());
        String envelope = objectMapper.writeValueAsString(new JobEventEnvelope(
                claimed.id(),
                claimed.eventType(),
                claimed.schemaVersion(),
                claimed.createdAt(),
                claimed.aggregateId(),
                payload));

        kafkaTemplate.send(TOPIC, jobId.toString(), envelope).get(10, TimeUnit.SECONDS);
        jdbcTemplate.update(
                "UPDATE outbox_events SET claim_expires_at = now() - interval '1 second' "
                        + "WHERE id = ?",
                claimed.id());
        assertThat(publisher.publishBatch()).isEqualTo(1);

        List<ConsumerRecord<String, String>> delivered = readRecordsForJob(jobId, 2);
        assertThat(delivered).hasSize(2);
        assertThat(delivered).extracting(record -> {
            try {
                return objectMapper.readTree(record.value()).path("eventId").asText();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).containsOnly(claimed.id().toString());
    }

    @Test
    void concurrentPublisherInstancesClaimAnEventOnlyOnce() throws Exception {
        UUID jobId = createJob("email.send", null);
        OutboxPublisher secondPublisher = new OutboxPublisher(
                outboxStore,
                kafkaTemplate,
                objectMapper,
                new SimpleMeterRegistry(),
                Clock.systemUTC(),
                TOPIC,
                50,
                8,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return publisher.publishBatch();
            });
            var second = executor.submit(() -> {
                start.await();
                return secondPublisher.publishBatch();
            });
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS))
                    .isEqualTo(1);
            assertThat(readRecordsForJob(jobId, 1)).hasSize(1);
            assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHED.name());
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID createJob(String jobType, Instant scheduledAt) {
        return jobSubmissionService.submit(
                new CreateJobRequest(jobType, Map.of("sample", true), scheduledAt, 3, 0),
                null,
                SYSTEM_OWNER).job().getId();
    }

    private String jobStatus(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                id);
    }

    private List<ConsumerRecord<String, String>> readRecordsForJob(UUID jobId, int expectedCount) {
        if (expectedCount == 0) {
            return List.of();
        }
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(200)).records(TOPIC)) {
                    if (record.key().equals(jobId.toString())) {
                        records.add(record);
                    }
                }
                if (records.size() >= expectedCount) {
                    break;
                }
            }
        }
        return records;
    }
}
