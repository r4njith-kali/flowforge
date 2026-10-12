package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.api.ApiExceptionHandler;
import io.flowforge.controlplane.job.domain.InvalidJobStateException;
import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobNotFoundException;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.service.JobSubmissionResult;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import io.flowforge.controlplane.job.service.JobService;
import io.flowforge.controlplane.security.JobOwner;
import io.flowforge.controlplane.security.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
@WithMockJwt
class JobControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final JobOwner OWNER =
            new JobOwner("https://identity.flowforge.test/realms/flowforge", "operator-1");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private JobSubmissionService jobSubmissionService;

    @Test
    void createReturnsCreatedResourceAndLocation() throws Exception {
        Job job = newJob();
        when(jobSubmissionService.submit(
                any(),
                eq(null),
                eq(OWNER))).thenReturn(new JobSubmissionResult(job, false));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": "email.send",
                                  "payload": {"to": "user@example.test"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/jobs/" + job.getId()))
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payload.to").value("user@example.test"));
    }

    @Test
    void createReturnsProblemDetailForValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jobType": " ",
                                  "payload": {},
                                  "maxAttempts": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void createRejectsInvalidIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Idempotency-Key", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"email.send","payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void listSupportsSafeSortingAndStatusFiltering() throws Exception {
        Job job = newJob();
        when(jobService.list(
                eq(JobStatus.PENDING),
                eq("email.send"),
                any(Pageable.class),
                eq(OWNER),
                eq(false)))
                .thenReturn(new PageImpl<>(
                        List.of(job),
                        PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "jobType")),
                        6));

        mockMvc.perform(get("/api/v1/jobs")
                        .param("status", "PENDING")
                        .param("jobType", "email.send")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sortBy", "jobType")
                        .param("direction", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(job.getId().toString()));

        verify(jobService).list(
                eq(JobStatus.PENDING),
                eq("email.send"),
                eq(PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "jobType"))),
                eq(OWNER),
                eq(false));
    }

    @Test
    void listRejectsUnapprovedSortFields() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").param("sortBy", "payload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
    }

    @Test
    void listRejectsPageSizesAboveTheMaximum() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getReturnsNotFoundAsProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.get(id, OWNER, false)).thenThrow(new JobNotFoundException(id));

        mockMvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/jobs/" + id));
    }

    @Test
    void cancelReturnsConflictForIneligibleState() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobService.cancel(id, OWNER, false))
                .thenThrow(new InvalidJobStateException("Cannot cancel"));

        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_STATE_CONFLICT"));
    }

    @Test
    void getRejectsMalformedIdentifier() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static Job newJob() {
        return Job.create(
                "email.send",
                Map.of("to", "user@example.test"),
                null,
                Job.DEFAULT_MAX_ATTEMPTS,
                0,
                NOW);
    }
}
