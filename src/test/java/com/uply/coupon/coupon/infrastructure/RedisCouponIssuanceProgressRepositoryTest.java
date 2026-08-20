package com.uply.coupon.coupon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisCouponIssuanceProgressRepositoryTest {

    private static final Long COUPON_ID = 100L;
    private static final String PENDING_KEY = "coupon:pending:100";

    @InjectMocks private RedisCouponIssuanceProgressRepository progressRepository;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    // pending 키를 PENDING 값과 24시간 TTL로 저장하는지 검증한다.
    @Test
    void markPendingStoresKeyForTwentyFourHours() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        progressRepository.markPending(COUPON_ID);

        verify(valueOperations).set(PENDING_KEY, "PENDING", Duration.ofMinutes(1440));
    }

    // Redis에 pending 키가 있으면 발급 진행 중으로 판단하는지 검증한다.
    @Test
    void isPendingReturnsTrueWhenKeyExists() {
        given(redisTemplate.hasKey(PENDING_KEY)).willReturn(true);

        boolean pending = progressRepository.isPending(COUPON_ID);

        assertThat(pending).isTrue();
    }

    // Redis에 pending 키가 없으면 발급 진행 중이 아니라고 판단하는지 검증한다.
    @Test
    void isPendingReturnsFalseWhenKeyDoesNotExist() {
        given(redisTemplate.hasKey(PENDING_KEY)).willReturn(false);

        boolean pending = progressRepository.isPending(COUPON_ID);

        assertThat(pending).isFalse();
    }

    // Consumer의 DB 저장 완료 후 해당 couponId의 pending 키를 삭제하는지 검증한다.
    @Test
    void clearDeletesPendingKey() {
        progressRepository.clear(COUPON_ID);

        verify(redisTemplate).delete(PENDING_KEY);
    }
}
