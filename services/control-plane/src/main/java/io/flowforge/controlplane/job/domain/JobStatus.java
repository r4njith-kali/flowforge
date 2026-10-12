package io.flowforge.controlplane.job.domain;

public enum JobStatus {
    PENDING,
    SCHEDULED,
    DISPATCHING,
    DISPATCHED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
