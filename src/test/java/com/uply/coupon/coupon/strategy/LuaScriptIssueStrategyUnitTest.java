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

import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class LuaScriptIssueStrategyUnitTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate redisTemplate;

    @Mock private CouponIdGenerator couponIdGenerator;

    @Mock private CouponSaveStrategy couponSaveStrategy;

    @Mock private CampaignRepository campaignRepository;

    @Mock private CampaignCacheWarmupService campaignCacheWarmupService;

    // 보상 지표 수집용. Counter.builder().register()가 실제 동작해야 하므로 순수 Mock을 쓸 수 없다.
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks private LuaScriptIssueStrategy luaScriptIssueStrategy;

    private LocalDateTime expireAt;
    private long expireAtEpochMillis;

    private LocalDateTime issuedAt;
    private long issuedAtEpochMillis;

    @BeforeEach
    void setUp() {
        // 밀리초 단위 precision 손실 방지를 위해 EpochMilli 기반 LocalDateTime 생성
        expireAtEpochMillis = 1780000000000L;
        expireAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);

        // Lua Script가 Redis TIME 기준으로 반환하는 발급 시각 (expireAt과 다른 값이어야 뒤바뀜을 잡을 수 있다)
        issuedAtEpochMillis = 1770000000000L;
        issuedAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(issuedAtEpochMillis), ZoneOffset.UTC);

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
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isEqualTo(couponId);

        // 저장 전략 save() 정상 호출 검증
        // issuedAt은 Lua가 반환한 Redis TIME 값이어야 한다 (JVM 시각이면 이 검증에서 깨진다)
        verify(couponSaveStrategy)
                .save(couponId, userId, campaignId, stockId, idempotencyKey, issuedAt, expireAt);
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
                .willReturn(List.of(-1L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.ALREADY_ISSUED);

        // 실패 시 저장 전략 미호출 검증
        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any(), any());
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
                .willReturn(List.of(-2L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.OUT_OF_STOCK);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Lua Script 결과가 -3(미웜업)이고 DB에 캠페인이 존재하지 않으면 CampaignNotFoundException이 발생한다")
    void issue_NotWarmedUp_AndNotInDb_ThrowsCampaignNotFoundException() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignRepository.existsById(campaignId)).willReturn(false);

        // Lua Script 결과 -3 반환 (캐시 미스)
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-3L, issuedAtEpochMillis, expireAtEpochMillis));

        // when & then
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CampaignNotFoundException.class);

        // DB 존재 여부 확인 호출 검증 및 Redis Script 1회 실행 검증
        verify(campaignRepository, times(1)).existsById(campaignId);
        verify(redisTemplate, times(1))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("Lua Script 결과가 -3(미웜업)이나 DB에는 존재하면 CAMPAIGN_NOT_CACHED 실패 결과가 반환된다")
    void issue_NotWarmedUp_ButExistsInDb_ReturnsCampaignNotCachedFail() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignRepository.existsById(campaignId)).willReturn(true);

        // Lua Script 결과 -3 반환 (캐시 미스)
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-3L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.CAMPAIGN_NOT_CACHED);

        // DB 존재 여부 확인 호출 검증 및 Redis Script 1회 실행 검증
        verify(campaignRepository, times(1)).existsById(campaignId);
        verify(redisTemplate, times(1))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
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
                .willReturn(List.of(-4L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.CAMPAIGN_NOT_OPEN);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any(), any());
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

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any(), any());
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
                .willReturn(List.of(-99L, issuedAtEpochMillis, expireAtEpochMillis));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.SYSTEM_ERROR);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any(), any(), any());
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
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // 2. 보상 스크립트는 Long을 돌려준다. 발급 스크립트와 같은 값으로 두면 보상이 실패로
        //    처리되어 SAVE_RESULT_UNKNOWN으로 승격되므로, 스크립트별로 따로 스텁한다.
        given(
                        redisTemplate.execute(
                                eq(luaScriptIssueStrategy.rollbackScript), anyList(), anyString()))
                .willReturn(1L);

        // 3. Kafka 이벤트 발행 실패(KAFKA_PUBLISH_FAILED) 예외 발생 모킹
        willThrow(new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(issuedAt),
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
    @DisplayName("Redis 보상 자체가 실패하면 확정 실패가 아니라 SAVE_RESULT_UNKNOWN으로 승격된다")
    void issue_RollbackFailed_EscalatesToUnknown() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // 보상 스크립트 실행 중 인프라 예외 발생
        given(
                        redisTemplate.execute(
                                eq(luaScriptIssueStrategy.rollbackScript), anyList(), anyString()))
                .willThrow(new RuntimeException("Redis 연결 끊김"));

        willThrow(new CouponIssueException(IssueFailReason.KAFKA_PUBLISH_FAILED))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(issuedAt),
                        eq(expireAt));

        // when & then
        // 보상이 실패하면 Redis 재고가 덜 복구된 상태로 남는다.
        // 확정 실패로 올려보내면 상위 계층이 멱등성 진행 키를 지워 재시도를 허용하고,
        // 재시도가 반복될수록 재고만 줄어든다. 그래서 결과 불명확으로 승격해야 한다.
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.SAVE_RESULT_UNKNOWN);
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
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // 3. Kafka 이벤트 발행 결과 불명확(KAFKA_PUBLISH_UNKNOWN) 예외 발생 모킹
        willThrow(new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(issuedAt),
                        eq(expireAt));

        // when & then
        // 예외가 상위로 그대로 전파되는지 검증
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.SAVE_RESULT_UNKNOWN);

        // Redis 보상 로직이 절대 실행되지 않았음을 검증
        // - 최초 차감용 Lua Script만 1회 실행됨
        verify(redisTemplate, times(1))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName(
            "DB 커넥션 획득 실패(CannotCreateTransactionException)로 트랜잭션 진입 전에 예외가 발생하면"
                    + " Redis 보상을 실행하고 CONNECTION_UNAVAILABLE을 던진다")
    void issue_CannotCreateTransactionException_RollbackRedisAndThrowsConnectionUnavailable() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);

        // 1. Redis 선점 성공 설정
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // 2. 보상 스크립트 성공 설정
        given(
                        redisTemplate.execute(
                                eq(luaScriptIssueStrategy.rollbackScript), anyList(), anyString()))
                .willReturn(1L);

        // 3. @Transactional 프록시가 메서드 본문 진입 전 커넥션 획득에 실패한 상황을 모킹.
        //    SyncMysqlSaveStrategy 내부 catch를 거치지 않고 원본 예외가 그대로 전파된다.
        willThrow(new CannotCreateTransactionException("커넥션 풀 고갈"))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(issuedAt),
                        eq(expireAt));

        // when & then
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.CONNECTION_UNAVAILABLE);

        // Redis 보상 로직 실행 검증 (선점 1회 + 보상 1회 = 총 2회 execute 호출)
        verify(redisTemplate, times(2))
                .execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("DB 커넥션 획득 실패 시 Redis 보상 자체가 실패하면 SAVE_RESULT_UNKNOWN으로 승격된다")
    void issue_CannotCreateTransactionException_RollbackFailed_EscalatesToUnknown() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L, issuedAtEpochMillis, expireAtEpochMillis));

        // 보상 스크립트 실행 중 인프라 예외 발생
        given(
                        redisTemplate.execute(
                                eq(luaScriptIssueStrategy.rollbackScript), anyList(), anyString()))
                .willThrow(new RuntimeException("Redis 연결 끊김"));

        willThrow(new CannotCreateTransactionException("커넥션 풀 고갈"))
                .given(couponSaveStrategy)
                .save(
                        anyLong(),
                        eq(userId),
                        eq(campaignId),
                        eq(stockId),
                        eq(idempotencyKey),
                        eq(issuedAt),
                        eq(expireAt));

        // when & then
        assertThatThrownBy(
                        () ->
                                luaScriptIssueStrategy.issue(
                                        campaignId, userId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.SAVE_RESULT_UNKNOWN);
    }
}
