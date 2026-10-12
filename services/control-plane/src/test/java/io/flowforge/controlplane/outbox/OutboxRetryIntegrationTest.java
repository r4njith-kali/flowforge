package io.flowforge.controlplane.outbox;

import io.flowforge.controlplane.job.api.CreateJobRequest;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import io.flowforge.controlplane.outbox.service.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "flowforge.kafka.publisher.enabled=false",
        "flowforge.kafka.publisher.max-attempts=2",
        "flowforge.kafka.publisher.claim-lease=PT30S",
        "flowforge.kafka.publisher.send-timeout=PT10S",
        "flowforge.kafka.publisher.retry-base-delay=PT0S",
        "flowforge.kafka.publisher.retry-max-delay=PT1S"
})
@Testcontainers
class OutboxRetryIntegrationTest {

    private static final String TOPIC = "flowforge.job-dispatch.v1";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobSubmissionService jobSubmissionService;

    @Autowired
    private OutboxPublisher publisher;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM jobs");
    }

    @Test
    void kafkaOutageRetriesAndRecoversAfterBrokerAcknowledgement() {
        UUID jobId = createJob();
        CompletableFuture<SendResult<String, String>> unavailable = new CompletableFuture<>();
        unavailable.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        CompletableFuture<SendResult<String, String>> acknowledged =
                CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .thenReturn(unavailable, acknowledged);

        assertThat(publisher.publishBatch()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class,
                jobId)).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.PENDING.name());

        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class,
                jobId)).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.DISPATCHED.name());
    }

    @Test
    void exhaustedPublicationAttemptsBecomeTerminalAndFailTheJob() {
        UUID jobId = createJob();
        CompletableFuture<SendResult<String, String>> unavailable = new CompletableFuture<>();
        unavailable.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .thenReturn(unavailable);

        assertThat(publisher.publishBatch()).isZero();
        assertThat(publisher.publishBatch()).isZero();
        assertThat(publisher.publishBatch()).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                jobId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT terminal_failure_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class,
                jobId)).isTrue();
        assertThat(jobStatus(jobId)).isEqualTo(JobStatus.FAILED.name());
    }

    private UUID createJob() {
        return jobSubmissionService.submit(
                new CreateJobRequest("email.send", Map.of(), null, 2, 0),
                null).job().getId();
    }

    private String jobStatus(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                id);
    }
}
