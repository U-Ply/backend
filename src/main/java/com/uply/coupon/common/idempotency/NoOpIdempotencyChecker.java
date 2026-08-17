package com.uply.coupon.common.idempotency;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 동시성 전략의 순수 성능 비교에서 Redis 멱등성 계층을 제외하기 위한 구현체다. 운영 용도로 사용하지 않는다. */
@Component
@ConditionalOnProperty(name = "coupon.idempotency.enabled", havingValue = "false")
public class NoOpIdempotencyChecker implements IdempotencyChecker {

    @Override
    public Optional<String> getCachedResponse(String idempotencyKey) {
        return Optional.empty();
    }

    @Override
    public void cacheResponse(String idempotencyKey, String responseBody, int httpStatus) {
        // 벤치마크에서는 최초 응답을 Redis에 저장하지 않는다.
    }

    @Override
    public void clearProgress(String idempotencyKey) {
        // 선점한 PROCESSING 키가 없으므로 해제할 작업도 없다.
    }
}
