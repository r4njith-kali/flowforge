package io.flowforge.controlplane.job.domain;

public enum JobStatus {
    PENDING,
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
