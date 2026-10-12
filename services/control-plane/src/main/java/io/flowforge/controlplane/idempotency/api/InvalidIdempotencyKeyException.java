package io.flowforge.controlplane.idempotency.api;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must contain 1 to 255 non-whitespace characters");
    }
}
