package com.uply.coupon.campaign.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 3단계 자동 트리거 테스트. 실제로 여러 인스턴스를 띄우지 않고도, Redis 락 선점 결과를 Mockito로 연속 스텁(성공→실패)해서 "다중 인스턴스 중 한 대만 실행"을
 * 재현한다.
 */
class CacheAutoRecoveryTriggerTest {

    private static final Long CAMPAIGN_ID = 1L;
    private static final int THRESHOLD = 3;
    private static final long WINDOW_SECONDS = 5;
    private static final long LOCK_SECONDS = 30;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CampaignCacheWarmupService campaignCacheWarmupService;
    private SimpleMeterRegistry meterRegistry;
    private CacheAutoRecoveryTrigger trigger;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        campaignCacheWarmupService = mock(CampaignCacheWarmupService.class);
        meterRegistry = new SimpleMeterRegistry();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        // 실행 스레드 검증을 단순화하려고 즉시 실행 Executor를 쓴다 — 비동기 자체가 아니라
        // "무엇을, 어떤 조건에서 실행하는지"가 이 테스트의 관심사다.
        trigger =
                new CacheAutoRecoveryTrigger(
                        redisTemplate,
                        campaignCacheWarmupService,
                        Runnable::run,
                        meterRegistry,
                        THRESHOLD,
                        WINDOW_SECONDS,
                        LOCK_SECONDS);
    }

    @Test
    @DisplayName("campaignId가 null이면 아무 것도 하지 않는다")
    void onCacheMiss_NullCampaignId_NoOp() {
        trigger.onCacheMiss(null);

        verifyNoInteractions(redisTemplate, campaignCacheWarmupService);
    }

    @Test
    @DisplayName("임계치 미만이면 락 시도도, 복구 실행도 하지 않는다")
    void onCacheMiss_BelowThreshold_DoesNotTrigger() {
        given(valueOperations.increment("cache:recovery:trigger-count:1")).willReturn(2L);

        trigger.onCacheMiss(CAMPAIGN_ID);

        verify(valueOperations, never())
                .setIfAbsent(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        verifyNoInteractions(campaignCacheWarmupService);
    }

    @Test
    @DisplayName("최초 임계치 도달(count=1) 시점에만 카운트 키에 시간창 TTL을 건다")
    void onCacheMiss_FirstHit_SetsWindowExpiry() {
        given(valueOperations.increment("cache:recovery:trigger-count:1")).willReturn(1L);

        trigger.onCacheMiss(CAMPAIGN_ID);

        verify(redisTemplate)
                .expire("cache:recovery:trigger-count:1", Duration.ofSeconds(WINDOW_SECONDS));
    }

    @Test
    @DisplayName("임계치 도달 + 락 선점 성공이면 복구를 실행하고 전용 카운터를 증가시킨다")
    void onCacheMiss_ThresholdReachedAndLockAcquired_TriggersRecovery() {
        given(valueOperations.increment("cache:recovery:trigger-count:1"))
                .willReturn(THRESHOLD + 2L);
        given(
                        valueOperations.setIfAbsent(
                                "cache:recovery:lock:1", "1", Duration.ofSeconds(LOCK_SECONDS)))
                .willReturn(true);
        given(campaignCacheWarmupService.recoverMissingCache(CAMPAIGN_ID)).willReturn(List.of());

        trigger.onCacheMiss(CAMPAIGN_ID);

        verify(campaignCacheWarmupService).recoverMissingCache(CAMPAIGN_ID);
        assertCounterEquals("coupon.cache.auto_recovery.triggered", 1.0);
    }

    // 다중 인스턴스 시뮬레이션: 두 "인스턴스"가 동시에 같은 캠페인의 임계치를 넘겼다고 가정한다.
    // Redis 락은 전역 자원이므로, 첫 번째 setIfAbsent만 성공(true)하고 두 번째는 실패(false)한다
    // — 이 순서 자체가 실제 Redis SET NX의 동작을 그대로 반영한다.
    @Test
    @DisplayName("다중 인스턴스 상황: 락을 먼저 잡은 한 쪽만 복구를 실행하고, 나머지는 건너뛴다")
    void onCacheMiss_MultipleInstances_OnlyOneAcquiresLockAndRecovers() {
        given(valueOperations.increment("cache:recovery:trigger-count:1"))
                .willReturn((long) THRESHOLD);
        given(
                        valueOperations.setIfAbsent(
                                "cache:recovery:lock:1", "1", Duration.ofSeconds(LOCK_SECONDS)))
                .willReturn(true, false); // 인스턴스 A: 성공, 인스턴스 B: 실패
        given(campaignCacheWarmupService.recoverMissingCache(CAMPAIGN_ID)).willReturn(List.of());

        trigger.onCacheMiss(CAMPAIGN_ID); // 인스턴스 A
        trigger.onCacheMiss(CAMPAIGN_ID); // 인스턴스 B (같은 트리거 인스턴스를 재사용해도 락은 Redis에 있다)

        verify(campaignCacheWarmupService, org.mockito.Mockito.times(1))
                .recoverMissingCache(CAMPAIGN_ID);
    }

    // 리뷰에서 지적된 핵심 결함: 락은 이미 잡혔는데(setIfAbsent 성공) Executor 큐가 가득 차
    // execute()가 RejectedExecutionException을 던지면, 복구는 한 번도 안 도는데 락만
    // lockSeconds 동안 남아 다음 시도(자동이든 수동이든)를 막는다. 이 테스트가 실패한다면
    // 그 결함이 재발한 것이다.
    @Test
    @DisplayName("Executor가 작업 제출을 거부하면 락을 즉시 해제하고 카운터도 증가시키지 않는다")
    void onCacheMiss_ExecutorRejects_ReleasesLockImmediatelyAndDoesNotIncrementCounter() {
        // given — 큐 포화를 흉내내는 Executor: 항상 제출을 거부한다.
        java.util.concurrent.Executor rejectingExecutor =
                task -> {
                    throw new java.util.concurrent.RejectedExecutionException("queue full");
                };
        CacheAutoRecoveryTrigger triggerWithFullQueue =
                new CacheAutoRecoveryTrigger(
                        redisTemplate,
                        campaignCacheWarmupService,
                        rejectingExecutor,
                        meterRegistry,
                        THRESHOLD,
                        WINDOW_SECONDS,
                        LOCK_SECONDS);

        given(valueOperations.increment("cache:recovery:trigger-count:1"))
                .willReturn((long) THRESHOLD);
        given(
                        valueOperations.setIfAbsent(
                                "cache:recovery:lock:1", "1", Duration.ofSeconds(LOCK_SECONDS)))
                .willReturn(true);

        triggerWithFullQueue.onCacheMiss(CAMPAIGN_ID);

        // then — 락을 잡았던 그 키를 즉시 지워야 lockSeconds를 기다리지 않고 재시도할 수 있다.
        verify(redisTemplate).delete("cache:recovery:lock:1");
        verifyNoInteractions(campaignCacheWarmupService);
        assertCounterEquals("coupon.cache.auto_recovery.triggered", 0.0);
    }

    @Test
    @DisplayName("복구 실행 도중 예외가 나도 트리거 판정 자체는 실패하지 않는다")
    void onCacheMiss_RecoveryThrows_DoesNotPropagate() {
        given(valueOperations.increment("cache:recovery:trigger-count:1"))
                .willReturn((long) THRESHOLD);
        given(
                        valueOperations.setIfAbsent(
                                "cache:recovery:lock:1", "1", Duration.ofSeconds(LOCK_SECONDS)))
                .willReturn(true);
        given(campaignCacheWarmupService.recoverMissingCache(CAMPAIGN_ID))
                .willThrow(new RuntimeException("Redis 순간 장애"));

        org.assertj.core.api.Assertions.assertThatCode(() -> trigger.onCacheMiss(CAMPAIGN_ID))
                .doesNotThrowAnyException();
    }

    private void assertCounterEquals(String name, double expected) {
        double actual = meterRegistry.get(name).counter().count();
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
