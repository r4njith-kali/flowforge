package io.flowforge.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.worker.domain.FailureKind;
import io.flowforge.worker.domain.JobExecutionException;
import io.flowforge.worker.domain.JobHandler;
import io.flowforge.worker.messaging.JobCreatedListener;
import io.flowforge.worker.persistence.JobExecutionStore;
import io.flowforge.worker.service.JobExecutionService;
import io.flowforge.worker.service.WorkerIdentity;
import io.flowforge.worker.service.WorkerLeaseScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "flowforge.worker.lease=PT30S",
        "flowforge.worker.heartbeat-interval-ms=5000",
        "flowforge.worker.recovery-interval-ms=3600000",
        "flowforge.worker.recovery-batch-size=10",
        "flowforge.worker.retry-base-delay=PT10S",
        "flowforge.worker.retry-max-delay=PT1M"
})
@Import(JobExecutionIntegrationTest.TestHandlers.class)
@Testcontainers
class JobExecutionIntegrationTest {

    private static final String TOPIC = "flowforge.job-dispatch.v1";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("worker-test-schema.sql");
    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    private static final AtomicInteger TRANSIENT_ATTEMPTS = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobExecutionStore store;

    @Autowired
    private JobExecutionService executionService;

    @Autowired
    private WorkerIdentity workerIdentity;

    @Autowired
    private JobCreatedListener listener;

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
        jdbcTemplate.update("DELETE FROM job_attempts");
        jdbcTemplate.update("DELETE FROM jobs");
        TRANSIENT_ATTEMPTS.set(0);
    }

    @Test
    void completesEchoJobAndPersistsAttemptResult() {
        UUID jobId = createJob("demo.echo", Map.of("message", "hello"), 3, null);
        var claim = store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100).orElseThrow();

        executionService.execute(claim);

        assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result -> 'echo' ->> 'message' FROM jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("hello");
        assertThat(attemptStatus(jobId, 1)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT worker_id FROM job_attempts WHERE job_id = ?", String.class, jobId))
                .isEqualTo(workerIdentity.value());
    }

    @Test
    void unsupportedJobTypeFailsPermanentlyWithoutRetry() {
        UUID jobId = createJob("unsupported.kind", Map.of(), 5, null);
        var claim = store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100).orElseThrow();

        executionService.execute(claim);

        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(attemptCount(jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_type FROM job_attempts WHERE job_id = ?", String.class, jobId))
                .isEqualTo("PERMANENT");
    }

    @Test
    void duplicateKafkaDeliveryExecutesAJobOnlyOnce() throws Exception {
        UUID jobId = createJob("demo.echo", Map.of("message", "once"), 5, null);
        String event = objectMapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID(),
                "eventType", "JobCreated",
                "schemaVersion", 1,
                "occurredAt", Instant.now().toString(),
                "aggregateId", jobId,
                "payload", Map.of("jobId", jobId, "jobType", "demo.echo")));

        kafkaTemplate.send(TOPIC, jobId.toString(), event).get(10, TimeUnit.SECONDS);
        awaitStatus(jobId, "COMPLETED");
        kafkaTemplate.send(TOPIC, jobId.toString(), event).get(10, TimeUnit.SECONDS);
        Thread.sleep(500);

        assertThat(attemptCount(jobId)).isEqualTo(1);
        assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentWorkersCannotClaimTheSameJob() throws Exception {
        UUID jobId = createJob("demo.echo", Map.of(), 5, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return store.claim(jobId, "worker-a", Duration.ofSeconds(30), 100);
            });
            var second = executor.submit(() -> {
                start.await();
                return store.claim(jobId, "worker-b", Duration.ofSeconds(30), 100);
            });
            start.countDown();

            assertThat((first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)).isEqualTo(1);
        }
        assertThat(attemptCount(jobId)).isEqualTo(1);
    }

    @Test
    void transientFailuresRetryAndExhaustTheConfiguredAttemptLimit() {
        UUID jobId = createJob("test.transient", Map.of(), 2, null);
        var first = store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100).orElseThrow();
        executionService.execute(first);

        assertThat(jobStatus(jobId)).isEqualTo("DISPATCHED");
        assertThat(attemptStatus(jobId, 1)).isEqualTo("RETRY_WAIT");
        assertThat(store.claim(jobId, "too-early", Duration.ofSeconds(30), 100)).isEmpty();
        jdbcTemplate.update("UPDATE jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", jobId);

        var second = store.claimRecoverable(
                workerIdentity.value(), 1, Duration.ofSeconds(30), 100).getFirst();
        executionService.execute(second);

        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(attemptCount(jobId)).isEqualTo(2);
        assertThat(attemptStatus(jobId, 2)).isEqualTo("FAILED");
        assertThat(TRANSIENT_ATTEMPTS.get()).isEqualTo(2);
    }

    @Test
    void expiredLeaseIsReclaimedWithANewFencingTokenAndStaleOwnerCannotComplete() {
        UUID jobId = createJob("demo.echo", Map.of(), 3, null);
        var stale = store.claim(jobId, "worker-old", Duration.ofSeconds(30), 100).orElseThrow();
        jdbcTemplate.update(
                "UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                jobId);
        assertThat(store.complete(stale, "{\"stale\":true}"))
                .isEqualTo(JobExecutionStore.CompletionResult.LOST_OWNERSHIP);

        var recovered = store.claimRecoverable("worker-new", 10, Duration.ofSeconds(30), 100).getFirst();

        assertThat(recovered.fencingToken()).isGreaterThan(stale.fencingToken());
        assertThat(attemptStatus(jobId, 1)).isEqualTo("ABANDONED");
        assertThat(store.complete(stale, "{\"stale\":true}"))
                .isEqualTo(JobExecutionStore.CompletionResult.LOST_OWNERSHIP);
        assertThat(store.complete(recovered, "{\"recovered\":true}"))
                .isEqualTo(JobExecutionStore.CompletionResult.COMPLETED);
        assertThat(jobStatus(jobId)).isEqualTo("COMPLETED");
    }

    @Test
    void leaseExpiryAtMaximumAttemptsFailsInsteadOfStrandingTheJob() {
        UUID jobId = createJob("demo.echo", Map.of(), 1, null);
        store.claim(jobId, "worker-crashed", Duration.ofSeconds(30), 100).orElseThrow();
        jdbcTemplate.update(
                "UPDATE jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                jobId);

        assertThat(store.claimRecoverable("worker-restart", 10, Duration.ofSeconds(30), 100)).isEmpty();
        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        assertThat(attemptStatus(jobId, 1)).isEqualTo("ABANDONED");
    }

    @Test
    void rejectedRecoveryTaskReleasesItsClaimWithoutConsumingAnAttempt() {
        UUID jobId = createJob("demo.echo", Map.of(), 3, null);
        var scheduler = new WorkerLeaseScheduler(
                store,
                executionService,
                workerIdentity,
                command -> {
                    throw new RejectedExecutionException("test executor is saturated");
                },
                Duration.ofSeconds(30),
                100,
                5000,
                10);

        scheduler.recoverExpiredAndRetryableJobs();

        assertThat(jobStatus(jobId)).isEqualTo("DISPATCHED");
        assertThat(attemptCount(jobId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id = ?", Integer.class, jobId))
                .isZero();
        assertThat(store.claim(jobId, "worker-after-rejection", Duration.ofSeconds(30), 100))
                .isPresent();
    }

    @Test
    void scheduledJobsAreClaimedOnlyAfterTheirScheduledTime() {
        UUID jobId = createJob("demo.echo", Map.of(), 3, Instant.now().plusSeconds(3600));

        assertThat(store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100)).isEmpty();
        jdbcTemplate.update("""
                UPDATE jobs SET scheduled_at = clock_timestamp() - INTERVAL '1 second',
                    next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, jobId);

        assertThat(store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100)).isPresent();
    }

    @Test
    void runningDelayJobCooperativelyCancelsAndPersistsCancelledState() throws Exception {
        UUID jobId = createJob("demo.delay", Map.of("delayMillis", 5000), 3, null);
        var claim = store.claim(jobId, workerIdentity.value(), Duration.ofSeconds(30), 100).orElseThrow();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var execution = executor.submit(() -> executionService.execute(claim));
            awaitAttemptRunning(jobId);
            jdbcTemplate.update("UPDATE jobs SET cancel_requested_at = clock_timestamp() WHERE id = ?", jobId);
            execution.get(5, TimeUnit.SECONDS);
        }

        assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
        assertThat(attemptStatus(jobId, 1)).isEqualTo("CANCELLED");
    }

    private UUID createJob(String type, Map<String, Object> payload, int maxAttempts, Instant scheduledAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO jobs (
                    id, job_type, payload, status, created_at, updated_at, scheduled_at,
                    max_attempts, retry_delay_seconds, next_attempt_at
                ) VALUES (?, ?, ?::jsonb, 'DISPATCHED', clock_timestamp(), clock_timestamp(),
                          ?, ?, 0, clock_timestamp())
                """,
                id,
                type,
                writeJson(payload),
                scheduledAt == null ? null : java.sql.Timestamp.from(scheduledAt),
                maxAttempts);
        return id;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String jobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String attemptStatus(UUID jobId, int attempt) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM job_attempts WHERE job_id = ? AND attempt_number = ?",
                String.class,
                jobId,
                attempt);
    }

    private int attemptCount(UUID jobId) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, jobId);
    }

    private void awaitStatus(UUID jobId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (expected.equals(jobStatus(jobId))) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(jobStatus(jobId)).isEqualTo(expected);
    }

    private void awaitAttemptRunning(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM job_attempts WHERE job_id = ? AND status = 'RUNNING'",
                    Integer.class,
                    jobId);
            if (count != null && count == 1) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Job attempt did not enter RUNNING");
    }

    @TestConfiguration
    static class TestHandlers {

        @Bean
        JobHandler transientFailureHandler() {
            return new JobHandler() {
                @Override
                public String jobType() {
                    return "test.transient";
                }

                @Override
                public Map<String, Object> execute(
                        Map<String, Object> payload,
                        io.flowforge.worker.domain.JobExecutionContext context) throws Exception {
                    TRANSIENT_ATTEMPTS.incrementAndGet();
                    throw new JobExecutionException(FailureKind.TRANSIENT, "retryable");
                }
            };
        }
    }
}
