package com.uply.coupon.common.idempotency;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 동시성 전략의 순수 성능 비교에서 Redis 멱등성 계층을 제외하기 위한 구현체다. 운영 용도로 사용하지 않는다. */
@Component
@ConditionalOnProperty(name = "coupon.idempotency.enabled", havingValue = "false")
public class NoOpIdempotencyChecker implements IdempotencyChecker {

    @Override
    public IdempotencyClaim acquire(String idempotencyKey, String requestHash) {
        // 벤치마크에서는 아무 것도 추적하지 않는다 - 매 호출을 항상 최초 요청으로 취급한다.
        return IdempotencyClaim.acquired(UUID.randomUUID().toString());
    }

    @Override
    public boolean complete(
            String idempotencyKey,
            String ownerToken,
            String requestHash,
            String responseBody,
            int httpStatus) {
        // 벤치마크에서는 최초 응답을 Redis에 저장하지 않는다.
        return true;
    }

    @Override
    public boolean release(String idempotencyKey, String ownerToken) {
        // 선점한 PROCESSING 키가 없으므로 해제할 작업도 없다.
        return true;
    }

    @Override
    public boolean renew(String idempotencyKey, String ownerToken) {
        return true;
    }
}
