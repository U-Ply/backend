package com.uply.coupon.coupon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.it.IntegrationTestContainers;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisCouponIssuanceProgressRepositoryIntegrationTest extends IntegrationTestContainers {

    @Autowired private RedisCouponIssuanceProgressRepository progressRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void countStale_방금_생성된_pending은_세지_않는다() {
        progressRepository.markPending(9101L);

        long stale = progressRepository.countStale(Duration.ofMinutes(10));

        assertThat(stale).isZero();
    }

    @Test
    void countStale_남은_TTL이_임계치보다_적은_키만_센다() {
        // 신선한 키: markPending 이 찍는 기본 24시간 TTL 그대로 둔다.
        progressRepository.markPending(9102L);
        // 오래된 키: 남은 TTL을 짧게 직접 세팅해 "많이 지난" 상태를 흉내낸다.
        // (24시간 - 10분 임계치 = 85800초보다 훨씬 짧은 100초)
        redisTemplate.opsForValue().set("coupon:pending:9103", "PENDING", Duration.ofSeconds(100));

        long stale = progressRepository.countStale(Duration.ofMinutes(10));

        assertThat(stale).isEqualTo(1);
    }

    @Test
    void countStale_pending이_아닌_키는_세지_않는다() {
        redisTemplate.opsForValue().set("other:key:1", "x", Duration.ofSeconds(1));

        long stale = progressRepository.countStale(Duration.ofMinutes(10));

        assertThat(stale).isZero();
    }
}
