package io.flowforge.controlplane.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.flowforge.controlplane.job.api.CreateJobRequest;
import io.flowforge.controlplane.job.domain.Job;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class JobRequestFingerprint {

    private final ObjectMapper objectMapper;

    public JobRequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String calculate(CreateJobRequest request) {
        ObjectNode canonical = JsonNodeFactory.instance.objectNode();
        canonical.put("jobType", request.jobType());
        canonical.set("payload", canonicalize(objectMapper.valueToTree(request.payload())));
        if (request.scheduledAt() == null) {
            canonical.putNull("scheduledAt");
        } else {
            canonical.put("scheduledAt", request.scheduledAt().toString());
        }
        canonical.put(
                "maxAttempts",
                request.maxAttempts() == null ? Job.DEFAULT_MAX_ATTEMPTS : request.maxAttempts());
        canonical.put(
                "retryDelaySeconds",
                request.retryDelaySeconds() == null ? 0 : request.retryDelaySeconds());
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to canonicalize job request", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                canonical.set(name, canonicalize(node.get(name)));
            }
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        if (node.isNumber()) {
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }
}
