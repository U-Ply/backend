package com.uply.coupon.common.exception;

/**
 * Kafka lag·DLT가 정착되지 않은 상태에서 캐시 복구(웜업)를 시도했을 때 던진다.
 *
 * <p>{@link IllegalStateException}을 상속해, 이 예외를 도입하기 전부터 있던 {@code
 * isInstanceOf(IllegalStateException.class)} 검증과 호환된다.
 */
public class CacheRecoveryNotSettledException extends IllegalStateException {

    public CacheRecoveryNotSettledException(String message) {
        super(message);
    }
}
