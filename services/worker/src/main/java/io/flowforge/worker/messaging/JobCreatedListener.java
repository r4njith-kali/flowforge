package io.flowforge.worker.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.worker.domain.JobExecutionClaim;
import io.flowforge.worker.persistence.JobExecutionStore;
import io.flowforge.worker.service.JobExecutionService;
import io.flowforge.worker.service.WorkerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class JobCreatedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobCreatedListener.class);

    private final ObjectMapper objectMapper;
    private final JobExecutionStore store;
    private final JobExecutionService executionService;
    private final WorkerIdentity workerIdentity;
    private final Duration lease;
    private final int maxAttempts;

    public JobCreatedListener(
            ObjectMapper objectMapper,
            JobExecutionStore store,
            JobExecutionService executionService,
            WorkerIdentity workerIdentity,
            @Value("${flowforge.worker.lease:PT1M}") Duration lease,
            @Value("${flowforge.worker.max-attempts:100}") int maxAttempts) {
        this.objectMapper = objectMapper;
        this.store = store;
        this.executionService = executionService;
        this.workerIdentity = workerIdentity;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
    }

    @KafkaListener(topics = "${flowforge.kafka.topics.job-dispatch}")
    public void onMessage(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        if (!"JobCreated".equals(event.path("eventType").asText())
                || event.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported job dispatch event contract");
        }
        UUID aggregateId = UUID.fromString(event.path("aggregateId").asText());
        UUID payloadJobId = UUID.fromString(event.path("payload").path("jobId").asText());
        if (!aggregateId.equals(payloadJobId)) {
            throw new IllegalArgumentException("Job dispatch aggregate ID does not match payload job ID");
        }

        var claim = store.claim(aggregateId, workerIdentity.value(), lease, maxAttempts);
        if (claim.isEmpty()) {
            LOGGER.atDebug()
                    .addKeyValue("jobId", aggregateId)
                    .log("Ignored duplicate or ineligible job dispatch event");
            return;
        }
        executionService.execute(claim.get());
    }
}
