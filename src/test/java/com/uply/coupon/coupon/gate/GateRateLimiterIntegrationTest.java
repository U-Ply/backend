package com.uply.coupon.coupon.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.it.IntegrationTestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class GateRateLimiterIntegrationTest extends IntegrationTestContainers {

    private static final String BUCKET_KEY = "gate:issue:tokens";

    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void clearBucket() {
        redisTemplate.delete(BUCKET_KEY);
    }

    @Test
    @DisplayName("capacity 만큼 통과한 뒤에는 429로 거부")
    void allowsUpToCapacityThenRejects() {
        GateRateLimiter limiter = new GateRateLimiter(redisTemplate, true, 5L, 0.01);

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(5);
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("시간이 지나면 토큰이 리필되어 다시 통과")
    void refillsOverTime() throws InterruptedException {
        GateRateLimiter limiter = new GateRateLimiter(redisTemplate, true, 2L, 0.5); // 2초당 1개 리필

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        Thread.sleep(2500); // 0.5/s * 2.5s ≈ 1개 리필 (2000ms면 딱 1개라 여유 두기)

        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("enabled=false 면 Redis 를 건드리지 않고 항상 통과")
    void disabledAlwaysPassesWithoutTouchingRedis() {
        GateRateLimiter limiter = new GateRateLimiter(redisTemplate, false, 1L, 1.0);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(redisTemplate.hasKey(BUCKET_KEY)).isFalse();
    }
}
