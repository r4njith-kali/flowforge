package io.flowforge.controlplane.job.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Request to create a durable job")
public record CreateJobRequest(
        @NotBlank
        @Size(max = 200)
        @Schema(description = "Handler type that will execute the job", example = "email.send")
        String jobType,

        @NotNull
        @Size(max = 1000)
        @Schema(description = "JSON object passed to the handler")
        Map<String, Object> payload,

        @Schema(description = "Optional time after which the job becomes eligible to run")
        Instant scheduledAt,

        @Min(1)
        @Max(100)
        @Schema(description = "Maximum execution attempts; defaults to 5", minimum = "1", maximum = "100")
        Integer maxAttempts,

        @Min(0)
        @Max(86400)
        @Schema(description = "Base retry delay in seconds; defaults to 0", minimum = "0", maximum = "86400")
        Integer retryDelaySeconds) {
}
