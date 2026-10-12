package io.flowforge.worker.domain;

public class JobExecutionException extends Exception {

    private final FailureKind failureKind;

    public JobExecutionException(FailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    public JobExecutionException(FailureKind failureKind, String message, Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public static JobExecutionException permanent(String message) {
        return new JobExecutionException(FailureKind.PERMANENT, message);
    }

    public static JobExecutionException transientFailure(String message, Throwable cause) {
        return new JobExecutionException(FailureKind.TRANSIENT, message, cause);
    }
}
