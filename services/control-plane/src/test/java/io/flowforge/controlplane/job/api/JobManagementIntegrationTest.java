package io.flowforge.controlplane.job.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.job.domain.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JobManagementIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearJobs() {
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
    void runningJobCannotBeCancelled() throws Exception {
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

        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_STATE_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                id)).isEqualTo(JobStatus.RUNNING.name());
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
