package io.flowforge.worker.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WorkerIdentity {

    private final String workerId = "worker-" + UUID.randomUUID();

    public String value() {
        return workerId;
    }
}
