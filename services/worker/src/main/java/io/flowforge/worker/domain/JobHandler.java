package io.flowforge.worker.domain;

import java.util.Map;

/**
 * Handlers should be idempotent (or use downstream idempotency keys), check
 * cancellation at safe points, and avoid non-recoverable side effects.
 */
public interface JobHandler {

    String jobType();

    Map<String, Object> execute(Map<String, Object> payload, JobExecutionContext context) throws Exception;
}
