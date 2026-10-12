package io.flowforge.worker.handler;

import io.flowforge.worker.domain.JobExecutionContext;
import io.flowforge.worker.domain.JobHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DemoEchoJobHandler implements JobHandler {

    @Override
    public String jobType() {
        return "demo.echo";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> payload, JobExecutionContext context) throws Exception {
        context.checkCancelled();
        return Map.of("echo", payload);
    }
}
