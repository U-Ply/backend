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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class LuaScriptIssueStrategyUnitTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock private CouponIdGenerator couponIdGenerator;

    @Mock private CouponSaveStrategy couponSaveStrategy;

    @InjectMocks private LuaScriptIssueStrategy luaScriptIssueStrategy;

    private LocalDateTime expireAt;
    private long expireAtEpochMillis;

    @BeforeEach
    void setUp() {
        // 밀리초 단위 precision 손실 방지를 위해 EpochMilli 기반 LocalDateTime 생성
        expireAtEpochMillis = 1780000000000L;
        expireAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);

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

        // Redis expireAt 조회 설정
        given(redisTemplate.opsForValue().get("campaign:" + campaignId + ":expireAt"))
                .willReturn(String.valueOf(expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isEqualTo(couponId);

        // 저장 전략 save() 정상 호출 검증
        verify(couponSaveStrategy)
                .save(couponId, userId, campaignId, stockId, idempotencyKey, expireAt);
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
        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any());
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

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any());
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

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any());
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

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any());
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

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any());
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

        // 2. Redis expireAt 조회 설정
        given(redisTemplate.opsForValue().get("campaign:" + campaignId + ":expireAt"))
                .willReturn(String.valueOf(expireAtEpochMillis));

        // 3. Kafka 이벤트 발행 실패(KAFKA_PUBLISH_FAILED) 예외 발생 모킹
        willThrow(new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(expireAt));

        // when & then
        // 예외 발생 여부 및 사유 검증
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_FAILED);

        // Redis 보상 로직 실행 검증 (선점 1회 + 보상 1회 = 총 2회 execute 호출)
        verify(redisTemplate, times(2))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("Kafka 저장 전략 실행 중 KAFKA_PUBLISH_UNKNOWN 발생 시 Redis 보상 로직을 실행하지 않고 예외를 그대로 전파한다")
    void issue_KafkaPublishUnknown_DoesNotRollbackRedisAndThrowsException() {
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

        // 2. Redis expireAt 조회 설정
        given(redisTemplate.opsForValue().get("campaign:" + campaignId + ":expireAt"))
                .willReturn(String.valueOf(expireAtEpochMillis));

        // 3. Kafka 이벤트 발행 결과 불명확(KAFKA_PUBLISH_UNKNOWN) 예외 발생 모킹
        willThrow(new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_UNKNOWN))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(expireAt));

        // when & then
        // 예외가 상위로 그대로 전파되는지 검증
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.KAFKA_PUBLISH_UNKNOWN);

        // Redis 보상 로직이 절대 실행되지 않았음을 검증
        // - 최초 차감용 Lua Script만 1회 실행됨
        verify(redisTemplate, times(1))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
    }
}
