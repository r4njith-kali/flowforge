package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.idempotency.api.InvalidIdempotencyKeyException;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.service.JobService;
import io.flowforge.controlplane.job.service.JobSubmissionService;
import io.flowforge.controlplane.security.JobOwner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Create and manage durable jobs")
public class JobController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final JobService jobService;
    private final JobSubmissionService jobSubmissionService;

    public JobController(JobService jobService, JobSubmissionService jobSubmissionService) {
        this.jobService = jobService;
        this.jobSubmissionService = jobSubmissionService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @Operation(summary = "Create a job")
    public ResponseEntity<JobResponse> create(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt principal) {
        if (idempotencyKey != null
                && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
            throw new InvalidIdempotencyKeyException();
        }
        var submission = jobSubmissionService.submit(
                request,
                idempotencyKey,
                JobOwner.from(principal));
        var job = submission.job();
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(job.getId())
                .toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    @Operation(summary = "List jobs")
    public JobPageResponse list(
            @Parameter(description = "Filter by lifecycle status")
            @RequestParam(name = "status", required = false) JobStatus status,
            @Parameter(description = "Filter by exact job type")
            @RequestParam(name = "jobType", required = false) String jobType,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(name = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(name = "direction", defaultValue = "DESC") Sort.Direction direction,
            @AuthenticationPrincipal Jwt principal) {
        if (jobType != null && (jobType.isBlank() || jobType.length() > 200)) {
            throw new InvalidJobQueryException("jobType filter must contain 1 to 200 characters");
        }

        Sort sort = Sort.by(direction, JobSortField.fromParameter(sortBy).property());
        return JobPageResponse.from(
                jobService.list(
                        status,
                        jobType,
                        PageRequest.of(page, size, sort),
                        JobOwner.from(principal),
                        isAdministrator(principal)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    @Operation(summary = "Get a job")
    public JobResponse get(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt principal) {
        return JobResponse.from(jobService.get(
                id,
                JobOwner.from(principal),
                isAdministrator(principal)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    @Operation(summary = "Cancel a job before dispatch is acknowledged")
    public ResponseEntity<JobResponse> cancel(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt principal) {
        JobResponse job = JobResponse.from(jobService.cancel(
                id,
                JobOwner.from(principal),
                isAdministrator(principal)));
        if (job.cancelRequestedAt() != null && job.status() == JobStatus.RUNNING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
        }
        return ResponseEntity.ok(job);
    }

    private static boolean isAdministrator(Jwt principal) {
        return new io.flowforge.controlplane.security.KeycloakRoleConverter()
                .convert(principal).stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private enum JobSortField {
        CREATED_AT("createdAt"),
        UPDATED_AT("updatedAt"),
        SCHEDULED_AT("scheduledAt"),
        JOB_TYPE("jobType"),
        STATUS("status");

        private final String property;

        JobSortField(String property) {
            this.property = property;
        }

        String property() {
            return property;
        }

        static JobSortField fromParameter(String parameter) {
            for (JobSortField field : values()) {
                if (field.property.equals(parameter)) {
                    return field;
                }
            }
            throw new InvalidJobQueryException(
                    "sortBy must be one of createdAt, updatedAt, scheduledAt, jobType, or status");
        }
    }
}
