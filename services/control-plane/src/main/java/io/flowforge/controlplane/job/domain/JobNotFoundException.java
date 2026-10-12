package io.flowforge.controlplane.job.domain;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super("Job not found: " + id);
    }
}
