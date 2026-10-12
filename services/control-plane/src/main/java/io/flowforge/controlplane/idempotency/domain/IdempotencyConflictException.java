package io.flowforge.controlplane.idempotency.domain;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with different request content");
    }
}
