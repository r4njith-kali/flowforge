package io.flowforge.controlplane.security;

import io.flowforge.controlplane.api.ApiExceptionHandler;
import io.flowforge.controlplane.config.SecurityConfiguration;
import io.flowforge.controlplane.job.api.JobController;
import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.service.JobService;
import io.flowforge.controlplane.job.service.JobSubmissionResult;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SecurityAccessTest {

    private static final String ISSUER = "https://identity.flowforge.test/realms/flowforge";
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private JobSubmissionService jobSubmissionService;

    @Test
    void unauthenticatedApiRequestsReturnStandardProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void healthCheckRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerCanReadButCannotCreateOrCancel() throws Exception {
        when(jobService.list(any(), any(), any(), any(), anyBoolean())).thenReturn(Page.empty());
        mockMvc.perform(get("/api/v1/jobs").with(asRole("VIEWER", "viewer-1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/jobs")
                        .with(asRole("VIEWER", "viewer-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"demo.echo","payload":{}}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/jobs/00000000-0000-0000-0000-000000000001/cancel")
                        .with(asRole("VIEWER", "viewer-1")))
                .andExpect(status().isForbidden());
        verify(jobSubmissionService, never()).submit(any(), any(), any());
    }

    @Test
    void operatorCanSubmitJobs() throws Exception {
        Job job = Job.create(
                "demo.echo",
                Map.of("message", "ok"),
                null,
                5,
                0,
                NOW,
                new JobOwner(ISSUER, "operator-1"));
        when(jobSubmissionService.submit(any(), eq(null), eq(new JobOwner(ISSUER, "operator-1"))))
                .thenReturn(new JobSubmissionResult(job, false));

        mockMvc.perform(post("/api/v1/jobs")
                        .with(asRole("OPERATOR", "operator-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobType":"demo.echo","payload":{"message":"ok"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(job.getId().toString()));
    }

    @Test
    void administrationRoleIsRequiredForAdministrativeRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics").with(asRole("OPERATOR", "operator-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/metrics").with(asRole("ADMIN", "admin-1")))
                .andExpect(status().isNotFound());
    }

    private static JwtRequestPostProcessor asRole(String role, String subject) {
        return jwt()
                .jwt(builder -> builder
                        .issuer(ISSUER)
                        .subject(subject)
                        .claim("aud", "flowforge-api")
                        .issuedAt(NOW)
                        .expiresAt(NOW.plusSeconds(300)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
