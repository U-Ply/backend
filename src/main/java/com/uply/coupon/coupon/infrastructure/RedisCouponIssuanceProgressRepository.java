package com.uply.coupon.coupon.infrastructure;

import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisCouponIssuanceProgressRepository implements CouponIssuanceProgressRepository {

    private static final String KEY_PATTERN = "coupon:pending:%d";
    private static final String SCAN_PATTERN = "coupon:pending:*";
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

    @Override
    public long countStale(Duration staleThreshold) {
        long staleAtRemainingSeconds = PENDING_TTL.toSeconds() - staleThreshold.toSeconds();
        long staleCount = 0;

        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
                Cursor<byte[]> cursor =
                        connection
                                .keyCommands()
                                .scan(
                                        ScanOptions.scanOptions()
                                                .match(SCAN_PATTERN)
                                                .count(200)
                                                .build())) {
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                // SCAN과 TTL 조회 사이에 키가 만료/삭제될 수 있다(-2, 또는 null)
                if (remaining != null && remaining >= 0 && remaining <= staleAtRemainingSeconds) {
                    staleCount++;
                }
            }
        }

        return staleCount;
    }

    private String key(Long couponId) {
        return KEY_PATTERN.formatted(couponId);
    }
}
