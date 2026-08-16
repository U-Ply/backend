package com.uply.coupon.common.exception;

public class IdempotencyRequestInProgressException extends RuntimeException {

    public IdempotencyRequestInProgressException() {
        super("The request with this Idempotency-Key is already in progress.");
    }
}
