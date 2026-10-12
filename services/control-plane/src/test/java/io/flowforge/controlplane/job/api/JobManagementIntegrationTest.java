package io.flowforge.controlplane.job.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.job.service.JobSubmissionResult;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import io.flowforge.controlplane.job.service.JobRequestFingerprint;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.security.JobOwner;
import io.flowforge.controlplane.security.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "flowforge.kafka.publisher.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Testcontainers
@WithMockJwt
class JobManagementIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final JobOwner OWNER =
            new JobOwner("https://identity.flowforge.test/realms/flowforge", "operator-1");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobSubmissionService jobSubmissionService;

    @Autowired
    private JobRequestFingerprint fingerprint;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearJobs() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM jobs");
    }

    @Test
    void actuatorHealthIsAvailableAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayCreatesJobsTable() {
        String jobsTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.jobs')",
                String.class);

        assertThat(jobsTable).isEqualTo("jobs");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.idempotency_records')",
                String.class)).isEqualTo("idempotency_records");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.outbox_events')",
                String.class)).isEqualTo("outbox_events");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.job_attempts')",
                String.class)).isEqualTo("job_attempts");
    }

    @Test
    void createPersistsJobAndJsonPayload() throws Exception {
        var result = mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "email.send",
                                  "payload": {"to": "user@example.test", "metadata": {"urgent": true}}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobType").value("email.send"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(5))
                .andReturn();

        UUID id = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload ->> 'to' FROM jobs WHERE id = ?",
                String.class,
                id)).isEqualTo("user@example.test");

        mockMvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.payload.metadata.urgent").value(true));
    }

    @Test
    void createPersistsOnePendingJobCreatedOutboxEvent() throws Exception {
        var result = mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"to":"user@example.test"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID jobId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class,
                jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT event_type, schema_version, published_at, attempt_count, payload ->> 'jobId' AS job_id "
                        + "FROM outbox_events WHERE aggregate_id = ?",
                jobId))
                .containsEntry("event_type", "JobCreated")
                .containsEntry("schema_version", 1)
                .containsEntry("published_at", null)
                .containsEntry("attempt_count", 0)
                .containsEntry("job_id", jobId.toString());
    }

    @Test
    void sameIdempotencyKeyAndEquivalentRequestReturnOriginalJob() throws Exception {
        var first = mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "submit-order-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"a":1,"b":2}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String originalId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("id").asText();

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "submit-order-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payload":{"b":2.0,"a":1.0},"jobType":"email.send","maxAttempts":5,"retryDelaySeconds":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(originalId));
    }

    @Test
    void idempotencyKeysAreIndependentAcrossValidatedUsers() {
        CreateJobRequest request = new CreateJobRequest(
                "email.send", Map.of("value", "same-key"), null, null, null);
        JobOwner anotherOwner = new JobOwner(
                "https://identity.flowforge.test/realms/flowforge",
                "operator-2");

        var first = jobSubmissionService.submit(request, "shared-key", OWNER);
        var second = jobSubmissionService.submit(request, "shared-key", anotherOwner);

        assertThat(first.job().getId()).isNotEqualTo(second.job().getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                "shared-key")).isEqualTo(2);
    }

    @Test
    @WithMockJwt(subject = "operator-1")
    void listDetailAndCancelAreConsistentlyRestrictedToTheOwner() throws Exception {
        var ownedJob = jobSubmissionService.submit(
                new CreateJobRequest("email.send", Map.of(), null, null, null),
                null,
                OWNER).job();
        var otherJob = jobSubmissionService.submit(
                new CreateJobRequest("email.send", Map.of(), null, null, null),
                null,
                new JobOwner(OWNER.issuer(), "operator-2")).job();

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ownedJob.getId().toString()));
        mockMvc.perform(get("/api/v1/jobs/{id}", otherJob.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", otherJob.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockJwt(role = "ADMIN", subject = "admin-1")
    void administratorCanListJobsCreatedByDifferentOwners() throws Exception {
        var first = jobSubmissionService.submit(
                new CreateJobRequest("email.send", Map.of(), null, null, null),
                null,
                OWNER).job();
        var second = jobSubmissionService.submit(
                new CreateJobRequest("email.send", Map.of(), null, null, null),
                null,
                new JobOwner(OWNER.issuer(), "operator-2")).job();

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/jobs/{id}", first.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/jobs/{id}", second.getId()))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void reusingIdempotencyKeyForDifferentContentReturnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "submit-order-43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"to":"first@example.test"}}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "submit-order-43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"to":"second@example.test"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredIdempotencyKeyCanBeReusedForNewContent() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "expired-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"to":"old@example.test"}}
                                """))
                .andExpect(status().isCreated());
        jdbcTemplate.update(
                "UPDATE idempotency_records SET expires_at = now() - interval '1 second' "
                        + ", created_at = now() - interval '2 seconds' "
                        + "WHERE idempotency_key = ?",
                "expired-key");

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", "expired-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{"to":"new@example.test"}}
                                """))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                "expired-key")).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalSubmissionsCreateOneJobAndOneOutboxEvent() throws Exception {
        int requestCount = 8;
        var executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CreateJobRequest request = new CreateJobRequest(
                "email.send",
                Map.of("to", "concurrent@example.test"),
                null,
                null,
                null);
        String requestFingerprint = fingerprint.calculate(request);
        List<java.util.concurrent.Future<JobSubmissionResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start submissions");
                    }
                    return jobSubmissionService.submit(request, "concurrent-key", OWNER);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<JobSubmissionResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            UUID jobId = results.getFirst().job().getId();
            assertThat(results).extracting(result -> result.job().getId())
                    .containsOnly(jobId);
            assertThat(results).filteredOn(JobSubmissionResult::replayed).hasSize(requestCount - 1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT request_fingerprint FROM idempotency_records WHERE idempotency_key = ?",
                    String.class,
                    "concurrent-key")).isEqualTo(requestFingerprint);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outboxFailureRollsBackJobAndIdempotencyRecord() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_job_created_outbox() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced outbox insert failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_job_created_outbox_trigger
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_job_created_outbox()
                """);
        try {
            assertThatThrownBy(() -> jobSubmissionService.submit(
                    new CreateJobRequest("email.send", Map.of(), null, null, null),
                    "rollback-key",
                    OWNER)).isInstanceOf(JpaSystemException.class);

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM jobs", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_job_created_outbox_trigger ON outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_job_created_outbox()");
        }
    }

    @Test
    void scheduledJobCanBeCancelledAndVersionAdvances() throws Exception {
        Instant scheduledAt = Instant.now().plusSeconds(300);
        var created = mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateJobRequest(
                                        "report.generate",
                                        java.util.Map.of(),
                                        scheduledAt,
                                        3,
                                        30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText());

        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                id)).isEqualTo(JobStatus.CANCELLED.name());
    }

    @Test
    void listFiltersAndBoundsPageResults() throws Exception {
        createJob("email.send");
        createJob("email.send");
        createJob("report.generate");

        mockMvc.perform(get("/api/v1/jobs")
                        .param("status", "PENDING")
                        .param("jobType", "email.send")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sortBy", "createdAt")
                        .param("direction", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].jobType").value("email.send"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void runningJobReceivesCooperativeCancellationRequest() throws Exception {
        var created = mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText());
        jdbcTemplate.update(
                "UPDATE jobs SET status = 'RUNNING', started_at = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(Instant.now()),
                id);

        var cancellation = mockMvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.cancelRequestedAt").isNotEmpty())
                .andReturn();
        long version = objectMapper.readTree(cancellation.getResponse().getContentAsString())
                .path("version").asLong();
        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(version));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                id)).isEqualTo(JobStatus.RUNNING.name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancel_requested_at FROM jobs WHERE id = ?",
                Timestamp.class,
                id)).isNotNull();
    }

    @Test
    void unknownJobReturnsNotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void openApiListsJobManagementOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/jobs'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/jobs'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/jobs/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/jobs/{id}/cancel'].post").exists());
    }

    private void createJob(String jobType) throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateJobRequest(jobType, java.util.Map.of(), null, null, null))))
                .andExpect(status().isCreated());
    }
}
