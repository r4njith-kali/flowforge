package io.flowforge.controlplane.job.api;

public class InvalidJobQueryException extends RuntimeException {

    public InvalidJobQueryException(String message) {
        super(message);
    }
}
