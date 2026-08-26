package com.uply.coupon.campaign.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * {@code CAMPAIGN_NOT_CACHED}가 짧은 시간창 안에 임계치 이상 발생하면 {@link
 * CampaignCacheWarmupService#recoverMissingCache}를 자동으로 트리거한다.
 *
 * <p>기본 비활성화({@code coupon.cache-recovery.auto-trigger-enabled=false}). 이 빈은 그 프로퍼티가 {@code true}일
 * 때만 생성되므로, {@link com.uply.coupon.common.exception.GlobalExceptionHandler}는 {@code
 * ObjectProvider}로 조회해 없으면(=비활성화) 그냥 건너뛴다 — V3 전용 {@code KafkaSettlementChecker}와 같은 패턴이다.
 *
 * <p>임계치 카운트는 Redis {@code INCR}로 세므로 API 서버가 여러 대여도 전체 인스턴스 합산으로 판정된다. 실제 복구 실행은 Redis 분산 락({@code
 * SET NX PX})으로 한 인스턴스만 수행한다 — 여러 인스턴스가 동시에 같은 캠페인을 복구하면 낭비이고, 서로 다른 타이밍에 REC-01류 자체 점검 로그가 중복돼 원인
 * 추적만 어려워진다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.cache-recovery.auto-trigger-enabled", havingValue = "true")
public class CacheAutoRecoveryTrigger {

    private static final String COUNT_KEY = "cache:recovery:trigger-count:%d";
    private static final String LOCK_KEY = "cache:recovery:lock:%d";

    private final StringRedisTemplate redisTemplate;
    private final CampaignCacheWarmupService campaignCacheWarmupService;
    private final Executor cacheRecoveryExecutor;
    private final Counter autoRecoveryTriggeredCounter;

    private final int thresholdCount;
    private final long windowSeconds;
    private final long lockSeconds;

    private DefaultRedisScript<Long> countScript;
    private DefaultRedisScript<Long> unlockScript;

    public CacheAutoRecoveryTrigger(
            StringRedisTemplate redisTemplate,
            CampaignCacheWarmupService campaignCacheWarmupService,
            Executor cacheRecoveryExecutor,
            MeterRegistry meterRegistry,
            @Value("${coupon.cache-recovery.threshold-count:5}") int thresholdCount,
            @Value("${coupon.cache-recovery.window-seconds:5}") long windowSeconds,
            @Value("${coupon.cache-recovery.lock-seconds:30}") long lockSeconds) {
        this.redisTemplate = redisTemplate;
        this.campaignCacheWarmupService = campaignCacheWarmupService;
        this.cacheRecoveryExecutor = cacheRecoveryExecutor;
        this.thresholdCount = thresholdCount;
        this.windowSeconds = windowSeconds;
        this.lockSeconds = lockSeconds;
        this.autoRecoveryTriggeredCounter =
                Counter.builder("coupon.cache.auto_recovery.triggered")
                        .description("CAMPAIGN_NOT_CACHED 임계치 도달로 자동 복구가 실제로 트리거된 횟수")
                        .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        countScript = new DefaultRedisScript<>();
        countScript.setScriptSource(
                new ResourceScriptSource(
                        new ClassPathResource("scripts/cache_recovery_count.lua")));
        countScript.setResultType(Long.class);

        unlockScript = new DefaultRedisScript<>();
        unlockScript.setScriptSource(
                new ResourceScriptSource(
                        new ClassPathResource("scripts/cache_recovery_unlock.lua")));
        unlockScript.setResultType(Long.class);
    }

    /**
     * 발급 요청 스레드에서 호출된다 — 여기서 시간이 걸리면 실패 응답(503) 자체가 늦어지므로, 임계치 판정과 락 선점까지만 동기로 하고 실제 복구는 {@link
     * #cacheRecoveryExecutor}로 넘긴다. 이 메서드 자체가 실패해도(Redis 순간 장애 등) 원래의 503 응답에는 영향을 주면 안 되므로 예외를
     * 삼킨다.
     */
    public void onCacheMiss(Long campaignId) {
        if (campaignId == null) {
            return;
        }

        try {
            long count = incrementAndGetCount(campaignId);
            if (count < thresholdCount) {
                return;
            }

            // 락 값으로 이 시도 전용 고유 토큰을 쓴다 — runRecovery가 끝난 뒤(성공/실패
            // 무관) 그 토큰을 가진 락만 지우는 compare-and-delete로 해제해야, 복구가
            // 오래 걸려 TTL이 먼저 만료된 뒤 다른 인스턴스가 새로 잡은 락을 대신
            // 지우는 사고를 막을 수 있다.
            String lockToken = UUID.randomUUID().toString();
            if (!tryAcquireLock(campaignId, lockToken)) {
                log.debug("자동 복구 락 선점 실패(다른 인스턴스가 이미 처리 중) — campaignId: {}", campaignId);
                return;
            }

            log.warn(
                    "CAMPAIGN_NOT_CACHED가 {}초 내 {}회 발생 — 자동 캐시 복구를 트리거한다. campaignId: {}",
                    windowSeconds,
                    thresholdCount,
                    campaignId);

            // 락은 이미 잡혔으므로, 작업 제출 자체가 실패하면(Executor 큐 포화 등) 곧바로
            // 풀어줘야 한다. 그러지 않으면 복구가 한 번도 안 돌았는데 lockSeconds 동안
            // 다음 시도(자동이든 수동이든)를 막는 셈이 된다 — 하필 이 기능이 필요한
            // "다수 캠페인이 동시에 CAMPAIGN_NOT_CACHED를 쏟아내는" 상황에서 가장
            // 발생하기 쉬운 실패 모드다. 카운터도 실제 제출에 성공했을 때만 올린다.
            try {
                cacheRecoveryExecutor.execute(() -> runRecovery(campaignId, lockToken));
            } catch (RejectedExecutionException e) {
                log.error(
                        "자동 복구 작업 제출 실패(Executor 포화) — 락을 즉시 해제한다. campaignId: {}", campaignId, e);
                releaseLock(campaignId, lockToken);
                return;
            }
            autoRecoveryTriggeredCounter.increment();
        } catch (Exception e) {
            log.error("자동 캐시 복구 트리거 판정 중 예외 발생 — campaignId: {}", campaignId, e);
        }
    }

    private void runRecovery(Long campaignId, String lockToken) {
        try {
            List<String> mismatches = campaignCacheWarmupService.recoverMissingCache(campaignId);
            log.info("자동 캐시 복구 완료 — campaignId: {}, mismatches: {}", campaignId, mismatches.size());
        } catch (Exception e) {
            log.error("자동 캐시 복구 실행 실패 — campaignId: {}", campaignId, e);
        } finally {
            // 성공하든 실패하든 여기서 반드시 해제한다 — 그러지 않으면 다음 시도가
            // lockSeconds(기본 30초) 동안 막힌다.
            releaseLock(campaignId, lockToken);
        }
    }

    private long incrementAndGetCount(Long campaignId) {
        String key = String.format(COUNT_KEY, campaignId);
        Long count =
                redisTemplate.execute(countScript, List.of(key), String.valueOf(windowSeconds));
        return count == null ? 0L : count;
    }

    private boolean tryAcquireLock(Long campaignId, String lockToken) {
        String key = String.format(LOCK_KEY, campaignId);
        Boolean acquired =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(key, lockToken, Duration.ofSeconds(lockSeconds));
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock(Long campaignId, String lockToken) {
        String key = String.format(LOCK_KEY, campaignId);
        Long deleted = redisTemplate.execute(unlockScript, List.of(key), lockToken);
        if (deleted == null || deleted == 0L) {
            log.warn(
                    "자동 복구 락 해제 실패 — 이미 TTL 만료 후 다른 인스턴스가 재획득했을 수 있다." + " campaignId: {}",
                    campaignId);
        }
    }
}
