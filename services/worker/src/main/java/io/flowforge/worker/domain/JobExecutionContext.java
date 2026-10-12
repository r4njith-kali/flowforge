package io.flowforge.worker.domain;

import java.util.UUID;

public record JobExecutionContext(UUID jobId, int attemptNumber, long fencingToken, CancellationCheck cancellationCheck) {

    public void checkCancelled() throws JobCancelledException {
        if (cancellationCheck.isCancellationRequested()) {
            throw new JobCancelledException();
        }
    }

    @FunctionalInterface
    public interface CancellationCheck {
        boolean isCancellationRequested();
    }
}
