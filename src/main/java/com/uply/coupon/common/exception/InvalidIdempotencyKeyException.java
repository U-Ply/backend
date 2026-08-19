package com.uply.coupon.common.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be a valid UUID v4.");
    }
}
