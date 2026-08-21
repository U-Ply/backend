package com.uply.coupon.coupon.infrastructure;

import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** Redis를 이용해 Kafka 비동기 발급의 MySQL 반영 대기 상태를 관리한다. */
@Repository
@RequiredArgsConstructor
public class RedisCouponIssuanceProgressRepository implements CouponIssuanceProgressRepository {

    private static final String KEY_PATTERN = "coupon:pending:%d";
    private static final String PENDING = "PENDING";
    private static final Duration PENDING_TTL = Duration.ofMinutes(1440);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void markPending(Long couponId) {
        redisTemplate.opsForValue().set(key(couponId), PENDING, PENDING_TTL);
    }

    @Override
    public boolean isPending(Long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(couponId)));
    }

    @Override
    public void clear(Long couponId) {
        redisTemplate.delete(key(couponId));
    }

    private String key(Long couponId) {
        return KEY_PATTERN.formatted(couponId);
    }
}
