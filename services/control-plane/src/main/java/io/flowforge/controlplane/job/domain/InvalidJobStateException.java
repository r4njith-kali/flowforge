package io.flowforge.controlplane.job.domain;

public class InvalidJobStateException extends RuntimeException {

    public InvalidJobStateException(String message) {
        super(message);
    }
}
