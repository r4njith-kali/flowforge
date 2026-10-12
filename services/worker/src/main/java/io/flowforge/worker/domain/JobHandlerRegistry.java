package io.flowforge.worker.domain;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> jobHandlers) {
        this.handlers = jobHandlers.stream().collect(Collectors.toUnmodifiableMap(
                JobHandler::jobType,
                Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("Duplicate job handler for type " + first.jobType());
                }));
    }

    public JobHandler find(String jobType) throws JobExecutionException {
        JobHandler handler = handlers.get(jobType);
        if (handler == null) {
            throw JobExecutionException.permanent("Unsupported job type: " + jobType);
        }
        return handler;
    }
}
