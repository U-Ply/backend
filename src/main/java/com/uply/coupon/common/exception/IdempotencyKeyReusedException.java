package com.uply.coupon.common.exception;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("The Idempotency-Key was already used for a different request.");
    }
}
