package io.flowforge.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.common.retry.RetryBackoff;
import io.flowforge.worker.domain.FailureKind;
import io.flowforge.worker.domain.JobCancelledException;
import io.flowforge.worker.domain.JobExecutionClaim;
import io.flowforge.worker.domain.JobExecutionContext;
import io.flowforge.worker.domain.JobExecutionException;
import io.flowforge.worker.domain.JobHandler;
import io.flowforge.worker.domain.JobHandlerRegistry;
import io.flowforge.worker.persistence.JobExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class JobExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobExecutionStore store;
    private final JobHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int maxAttempts;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;

    public JobExecutionService(
            JobExecutionStore store,
            JobHandlerRegistry handlerRegistry,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${flowforge.worker.max-attempts:100}") int maxAttempts,
            @Value("${flowforge.worker.retry-base-delay:PT1S}") Duration retryBaseDelay,
            @Value("${flowforge.worker.retry-max-delay:PT5M}") Duration retryMaxDelay) {
        this.store = store;
        this.handlerRegistry = handlerRegistry;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.maxAttempts = maxAttempts;
        this.retryBaseDelay = retryBaseDelay;
        this.retryMaxDelay = retryMaxDelay;
        if (maxAttempts < 1
                || retryBaseDelay.isNegative()
                || retryMaxDelay.isNegative()
                || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("Invalid worker attempt or retry-delay configuration");
        }
    }

    public void execute(JobExecutionClaim claim) {
        long started = System.nanoTime();
        meterRegistry.counter("flowforge.worker.executions.started").increment();
        try {
            JobHandler handler = handlerRegistry.find(claim.jobType());
            JobExecutionContext context = new JobExecutionContext(
                    claim.jobId(),
                    claim.attemptNumber(),
                    claim.fencingToken(),
                    () -> store.cancellationRequested(claim));
            Map<String, Object> result = handler.execute(claim.payload(), context);
            context.checkCancelled();
            JobExecutionStore.CompletionResult outcome = store.complete(claim, writeJson(result));
            if (outcome == JobExecutionStore.CompletionResult.COMPLETED) {
                meterRegistry.counter("flowforge.worker.executions.completed").increment();
                LOGGER.atInfo()
                        .addKeyValue("jobId", claim.jobId())
                        .addKeyValue("attempt", claim.attemptNumber())
                        .addKeyValue("fencingToken", claim.fencingToken())
                        .log("Job execution completed");
            } else if (outcome == JobExecutionStore.CompletionResult.CANCELLED) {
                meterRegistry.counter("flowforge.worker.executions.cancelled").increment();
                LOGGER.atInfo()
                        .addKeyValue("jobId", claim.jobId())
                        .addKeyValue("attempt", claim.attemptNumber())
                        .log("Job execution completed after cancellation was requested");
            } else {
                meterRegistry.counter("flowforge.worker.executions.fenced").increment();
                LOGGER.atWarn()
                        .addKeyValue("jobId", claim.jobId())
                        .addKeyValue("attempt", claim.attemptNumber())
                        .addKeyValue("fencingToken", claim.fencingToken())
                        .log("Ignored completion from a worker that lost its lease");
            }
        } catch (JobCancelledException exception) {
            JobExecutionStore.CompletionResult outcome = store.complete(claim, null);
            if (outcome == JobExecutionStore.CompletionResult.CANCELLED) {
                meterRegistry.counter("flowforge.worker.executions.cancelled").increment();
                LOGGER.atInfo()
                        .addKeyValue("jobId", claim.jobId())
                        .addKeyValue("attempt", claim.attemptNumber())
                        .log("Cooperative job cancellation observed");
            } else if (outcome == JobExecutionStore.CompletionResult.LOST_OWNERSHIP) {
                meterRegistry.counter("flowforge.worker.executions.fenced").increment();
            } else {
                meterRegistry.counter("flowforge.worker.executions.completed").increment();
            }
        } catch (Exception exception) {
            recordFailure(claim, exception);
        } finally {
            Timer.builder("flowforge.worker.execution.duration")
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void recordFailure(JobExecutionClaim claim, Exception exception) {
        FailureKind kind = exception instanceof JobExecutionException executionException
                ? executionException.failureKind()
                : FailureKind.TRANSIENT;
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        String error = writeJson(Map.of(
                "failureType", kind.name(),
                "message", message.length() <= 2000 ? message : message.substring(0, 2000)));
        Duration delay = retryDelay(claim);
        JobExecutionStore.FailureResult outcome = store.fail(
                claim, kind, error, maxAttempts, delay);
        meterRegistry.counter("flowforge.worker.executions.failures", "classification", kind.name().toLowerCase())
                .increment();
        if (outcome == JobExecutionStore.FailureResult.RETRY_SCHEDULED) {
            meterRegistry.counter("flowforge.worker.executions.retries").increment();
            LOGGER.atWarn()
                    .addKeyValue("jobId", claim.jobId())
                    .addKeyValue("attempt", claim.attemptNumber())
                    .addKeyValue("failureType", kind)
                    .addKeyValue("retryDelayMs", delay.toMillis())
                    .setCause(exception)
                    .log("Job execution failed; retry scheduled");
        } else if (outcome == JobExecutionStore.FailureResult.TERMINAL) {
            meterRegistry.counter("flowforge.worker.executions.terminal_failures").increment();
            LOGGER.atError()
                    .addKeyValue("jobId", claim.jobId())
                    .addKeyValue("attempt", claim.attemptNumber())
                    .addKeyValue("failureType", kind)
                    .setCause(exception)
                    .log("Job execution failed terminally");
        } else if (outcome == JobExecutionStore.FailureResult.CANCELLED) {
            meterRegistry.counter("flowforge.worker.executions.cancelled").increment();
        } else {
            meterRegistry.counter("flowforge.worker.executions.fenced").increment();
        }
    }

    private Duration retryDelay(JobExecutionClaim claim) {
        long configuredBase = retryBaseDelay.toMillis();
        long jobBase = Duration.ofSeconds(claim.retryDelaySeconds()).toMillis();
        long base = Math.max(configuredBase, jobBase);
        return RetryBackoff.fullJitter(
                Duration.ofMillis(base),
                retryMaxDelay,
                claim.attemptNumber(),
                ThreadLocalRandom.current().nextDouble());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize job result", exception);
        }
    }
}
