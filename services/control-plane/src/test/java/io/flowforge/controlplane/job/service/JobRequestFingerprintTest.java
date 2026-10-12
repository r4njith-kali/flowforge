package io.flowforge.controlplane.job.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.job.api.CreateJobRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobRequestFingerprintTest {

    private final JobRequestFingerprint fingerprint =
            new JobRequestFingerprint(new ObjectMapper().findAndRegisterModules());

    @Test
    void fingerprintsIgnoreObjectPropertyOrderAndEquivalentNumericScale() {
        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("z", 1);
        firstPayload.put("nested", Map.of("b", 2, "a", 1));

        Map<String, Object> secondNested = new LinkedHashMap<>();
        secondNested.put("a", 1.0);
        secondNested.put("b", 2);
        Map<String, Object> secondPayload = new LinkedHashMap<>();
        secondPayload.put("nested", secondNested);
        secondPayload.put("z", 1.0);

        String first = fingerprint.calculate(new CreateJobRequest(
                "email.send", firstPayload, null, null, null));
        String second = fingerprint.calculate(new CreateJobRequest(
                "email.send", secondPayload, null, 5, 0));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void fingerprintsIncludeAllSubmissionSettings() {
        CreateJobRequest request = new CreateJobRequest(
                "email.send",
                Map.of("to", "a@example.test"),
                Instant.parse("2026-10-13T00:00:00Z"),
                4,
                10);

        assertThat(fingerprint.calculate(request))
                .isNotEqualTo(fingerprint.calculate(new CreateJobRequest(
                        "email.send",
                        Map.of("to", "b@example.test"),
                        request.scheduledAt(),
                        request.maxAttempts(),
                        request.retryDelaySeconds())));
    }
}
