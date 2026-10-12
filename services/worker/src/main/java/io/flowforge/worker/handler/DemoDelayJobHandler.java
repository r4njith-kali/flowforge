package io.flowforge.worker.handler;

import io.flowforge.worker.domain.JobExecutionContext;
import io.flowforge.worker.domain.JobExecutionException;
import io.flowforge.worker.domain.JobHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DemoDelayJobHandler implements JobHandler {

    private static final long MAX_DELAY_MILLIS = 30_000;
    private static final long CHECK_INTERVAL_MILLIS = 100;

    @Override
    public String jobType() {
        return "demo.delay";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> payload, JobExecutionContext context) throws Exception {
        Object value = payload.get("delayMillis");
        if (!(value instanceof Number delayValue)) {
            throw JobExecutionException.permanent("delayMillis must be an integer");
        }
        long delayMillis = delayValue.longValue();
        if (delayMillis < 0 || delayMillis > MAX_DELAY_MILLIS || delayValue.doubleValue() != delayMillis) {
            throw JobExecutionException.permanent("delayMillis must be between 0 and 30000");
        }

        long deadline = System.nanoTime() + delayMillis * 1_000_000;
        while (System.nanoTime() < deadline) {
            context.checkCancelled();
            long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            Thread.sleep(Math.min(CHECK_INTERVAL_MILLIS, remainingMillis));
        }
        context.checkCancelled();
        return Map.of("delayedMillis", delayMillis);
    }
}
