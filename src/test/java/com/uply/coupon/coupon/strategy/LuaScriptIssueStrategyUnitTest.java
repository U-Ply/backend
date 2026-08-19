package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class LuaScriptIssueStrategyUnitTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock private CouponIdGenerator couponIdGenerator;

    @Mock private CouponSaveStrategy couponSaveStrategy;

    @InjectMocks private LuaScriptIssueStrategy luaScriptIssueStrategy;

    @BeforeEach
    void setUp() {
        luaScriptIssueStrategy.init();
    }

    @Test
    @DisplayName("Lua Script 실행 성공(1) 시 DB 저장 전략을 호출하고 성공 결과를 반환한다")
    void issue_Success() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isEqualTo(couponId);

        // 저장 전략 save() 정상 호출 검증
        verify(couponSaveStrategy).save(couponId, userId, campaignId, stockId, idempotencyKey);
    }

    @Test
    @DisplayName("이미 발급된 유저(-1)인 경우 저장 전략을 호출하지 않고 ALREADY_ISSUED를 반환한다")
    void issue_Fail_AlreadyIssued() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-1L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.ALREADY_ISSUED);

        // 실패 시 저장 전략 미호출 검증
        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재고가 부족한 경우(-2) 저장 전략을 호출하지 않고 OUT_OF_STOCK을 반환한다")
    void issue_Fail_OutOfStock() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-2L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.OUT_OF_STOCK);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("캠페인 오픈 전인 경우(-4) 저장 전략을 호출하지 않고 CAMPAIGN_NOT_OPEN을 반환한다")
    void issue_Fail_CampaignNotOpen() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-4L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.CAMPAIGN_NOT_OPEN);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Redis 응답이 빈 리스트인 경우 저장 전략을 호출하지 않고 SYSTEM_ERROR를 반환한다")
    void issue_Fail_EmptyRedisResult() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(Collections.emptyList());

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.SYSTEM_ERROR);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("정의되지 않은 스크립트 반환 코드(-99)인 경우 SYSTEM_ERROR를 반환한다")
    void issue_Fail_UnknownResultCode() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-99L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.SYSTEM_ERROR);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Kafka 저장 전략 실행 중 예외 발생 시 Redis 보상 로직(재고 및 발급유저 원상복구)이 실행된다")
    void issue_KafkaPublishFailed_RollbackRedisAndThrowsException() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);

        // 1. Redis 선점 성공 설정
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L));

        // 2. Kafka 이벤트 발행 실패(KAFKA_PUBLISH_FAILED) 예외 발생 모킹
        willThrow(new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED))
                .given(couponSaveStrategy)
                .save(anyLong(), eq(userId), eq(campaignId), eq(stockId), eq(idempotencyKey));

        // when & then
        // 3. 예외 발생 여부 및 사유 검증
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_FAILED);

        // 4. Redis 보상 로직 실행 검증 (선점 1회 + 보상 1회 = 총 2회 execute 호출)
        // 최초 차감용 Lua Script 호출 검증 (1회)
        verify(redisTemplate, times(1))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
        // 2. 보상 로직(opsFor...) 호출 검증
        verify(redisTemplate, times(1)).execute(any(SessionCallback.class));
        //		verify(redisTemplate.opsForValue()).increment(anyString());
        //		verify(redisTemplate.opsForSet()).remove(anyString(), anyString());

    }
}
