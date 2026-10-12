package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @Operation(summary = "Create a job")
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        var job = jobService.create(
                request.jobType(),
                request.payload(),
                request.scheduledAt(),
                request.maxAttempts(),
                request.retryDelaySeconds());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(job.getId())
                .toUri();
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    @GetMapping
    @Operation(summary = "List jobs")
    public JobPageResponse list(
            @Parameter(description = "Filter by lifecycle status")
            @RequestParam(required = false) JobStatus status,
            @Parameter(description = "Filter by exact job type")
            @RequestParam(required = false) String jobType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        if (jobType != null && (jobType.isBlank() || jobType.length() > 200)) {
            throw new InvalidJobQueryException("jobType filter must contain 1 to 200 characters");
        }

        Sort sort = Sort.by(direction, JobSortField.fromParameter(sortBy).property());
        return JobPageResponse.from(
                jobService.list(status, jobType, PageRequest.of(page, size, sort)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a job")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending or scheduled job")
    public ResponseEntity<JobResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(JobResponse.from(jobService.cancel(id)));
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
