package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CacheRecoveryNotSettledException;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.service.KafkaSettlementChecker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 개시 전 RDB의 캠페인 재고 데이터를 Redis 캐시에 사전 적재(Warm-up) 및 장애 복구하는 서비스
 *
 * <pre>
 * 복구 실행 선행 조건 (Disaster Recovery Prerequisites)
 *   1. 신규 발급 트래픽 차단 (Gateway 수준)
 *   2. Kafka Consumer Lag = 0 및 DLT 처리 완료 확인 (DB의 최종 정합성 보장)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignCacheWarmupService {

    private static final String KEY_STOCK = "stock:%d";
    private static final String KEY_STOCK_ID = "stockId:%d:%s:%s";
    private static final String KEY_CAMPAIGN_OPEN_AT = "campaign:%d:openAt";
    private static final String KEY_CAMPAIGN_EXPIRE_AT = "campaign:%d:expireAt";
    private static final String KEY_ISSUED = "issued:%d";
    private static final String KEY_TEMP_ISSUED = "temp:issued:%d";

    private final CampaignRepository campaignRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository;
    private final StringRedisTemplate redisTemplate;

    // V3(coupon.save.strategy=kafka)에서만 빈이 존재한다. V0~V2에서는 비어 있으므로
    // 직접 주입하면 컨텍스트 기동이 깨진다.
    private final ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;

    private final MeterRegistry meterRegistry;

    private final Map<Long, CacheReadySnapshot> readyByCampaign = new ConcurrentHashMap<>();
    private final Set<Long> gaugeRegistered = ConcurrentHashMap.newKeySet();

    private record CacheReadySnapshot(Instant completedAt, Instant openAt) {}

    @Transactional(readOnly = true)
    public void warmupCampaign(Long campaignId) {
        log.info("Starting cache warm-up and recovery for campaignId: {}", campaignId);

        // 0. Kafka 정착 확인 (V3 전용)
        requireKafkaSettled();

        // 1. 해당 캠페인의 전체 Stock 목록 DB 조회
        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
            if (!campaignRepository.existsById(campaignId)) {
                throw new CampaignNotFoundException(campaignId);
            }
            log.warn("No stocks found for campaignId: {}", campaignId);
            return;
        }

        // 2. 오픈 시각 및 만료 시각 캐싱
        long openAtEpochMillis =
                stocks.get(0).getCampaign().getOpenAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate
                .opsForValue()
                .set(
                        String.format(KEY_CAMPAIGN_OPEN_AT, campaignId),
                        String.valueOf(openAtEpochMillis));

        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate
                .opsForValue()
                .set(
                        String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId),
                        String.valueOf(expireAtEpochMillis));

        // 3. stock:{stockId} 잔여 재고(remainingStock) 기준 캐싱
        for (CampaignStock stock : stocks) {
            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(KEY_STOCK, stock.getId()),
                            String.valueOf(stock.getRemainingStock()));

            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(
                                    KEY_STOCK_ID,
                                    campaignId,
                                    stock.getRouteId(),
                                    stock.getFareClass()),
                            String.valueOf(stock.getId()));
        }

        // 4. issued:{campaignId} Set 원자적 재구축 (RENAME 활용)
        rebuildIssuedSet(campaignId);

        log.info(
                "Cache warm-up and recovery completed successfully for campaignId: {}", campaignId);
        markCacheReady(campaignId, stocks);
    }

    @Transactional(readOnly = true)
    public List<String> recoverMissingCache(Long campaignId) {
        log.info("Starting selective cache recovery for campaignId: {}", campaignId);

        requireKafkaSettled();

        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
            if (!campaignRepository.existsById(campaignId)) {
                throw new CampaignNotFoundException(campaignId);
            }
            log.warn("No stocks found for campaignId: {}", campaignId);
            return List.of();
        }

        long openAtEpochMillis =
                stocks.get(0).getCampaign().getOpenAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();

        setIfAbsent(
                String.format(KEY_CAMPAIGN_OPEN_AT, campaignId), String.valueOf(openAtEpochMillis));
        setIfAbsent(
                String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId),
                String.valueOf(expireAtEpochMillis));

        for (CampaignStock stock : stocks) {
            setIfAbsent(
                    String.format(KEY_STOCK, stock.getId()),
                    String.valueOf(stock.getRemainingStock()));
            setIfAbsent(
                    String.format(
                            KEY_STOCK_ID, campaignId, stock.getRouteId(), stock.getFareClass()),
                    String.valueOf(stock.getId()));
        }

        rebuildIssuedSetIfMissing(campaignId);

        List<String> mismatches = verifyRecoveredStocks(stocks);
        if (mismatches.isEmpty()) {
            log.info("Post-recovery consistency check passed for campaignId: {}", campaignId);
        } else {
            log.warn(
                    "Post-recovery consistency check found {} mismatch(es) for campaignId {}: {}",
                    mismatches.size(),
                    campaignId,
                    mismatches);
        }

        log.info("Selective cache recovery completed for campaignId: {}", campaignId);
        markCacheReady(campaignId, stocks);
        return mismatches;
    }

    private void requireKafkaSettled() {
        KafkaSettlementChecker checker = kafkaSettlementCheckerProvider.getIfAvailable();
        if (checker == null) {
            return;
        }

        KafkaSettlement settlement = checker.check();
        if (!settlement.settled()) {
            throw new CacheRecoveryNotSettledException(
                    "Kafka 미정착 상태에서는 캐시 복구를 실행할 수 없습니다. lag="
                            + settlement.lag()
                            + ", dlt="
                            + settlement.dltCount());
        }
    }

    private void markCacheReady(Long campaignId, List<CampaignStock> stocks) {
        Instant openAt = stocks.get(0).getCampaign().getOpenAt().toInstant(ZoneOffset.UTC);
        readyByCampaign.put(campaignId, new CacheReadySnapshot(Instant.now(), openAt));

        if (gaugeRegistered.add(campaignId)) { // 캠페인당 한 번만 등록
            Gauge.builder(
                            "coupon.campaign.cache.ready",
                            readyByCampaign,
                            m -> m.containsKey(campaignId) ? 1d : 0d)
                    .tag("campaign", String.valueOf(campaignId))
                    .description("캠페인별 Redis 캐시 워밍업 완료 여부 (1=완료)")
                    .register(meterRegistry);

            Gauge.builder(
                            "coupon.campaign.cache.ready.lead.seconds",
                            readyByCampaign,
                            m -> {
                                CacheReadySnapshot s = m.get(campaignId);
                                return s == null
                                        ? Double.NaN
                                        : (double)
                                                Duration.between(s.completedAt(), s.openAt())
                                                        .toSeconds();
                            })
                    .tag("campaign", String.valueOf(campaignId))
                    .description("openAt - 워밍업 완료시각(초). 양수면 오픈 전 준비 완료")
                    .register(meterRegistry);
        }
    }

    private void rebuildIssuedSet(Long campaignId) {
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        String tempIssuedKey = String.format(KEY_TEMP_ISSUED, campaignId);

        // 이전 미완료 작업으로 잔존할 수 있는 임시 키 제거
        redisTemplate.delete(tempIssuedKey);

        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);

        if (issuedUserIds == null || issuedUserIds.isEmpty()) {
            redisTemplate.delete(issuedKey);
            log.info("Cleared issued Set for campaignId: {} (DB에 발급 이력 없음)", campaignId);
        } else {
            String[] userIdStrs =
                    issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);

            redisTemplate.opsForSet().add(tempIssuedKey, userIdStrs);
            redisTemplate.rename(tempIssuedKey, issuedKey);

            log.info(
                    "Atomically replaced issued Set for campaignId: {} with {} users",
                    campaignId,
                    userIdStrs.length);
        }
    }

    // SETNX. 키가 이미 있으면 손대지 않고 false를 반환한다 — {@link #recoverMissingCache} 전용
    private boolean setIfAbsent(String key, String value) {
        Boolean applied = redisTemplate.opsForValue().setIfAbsent(key, value);
        return Boolean.TRUE.equals(applied);
    }

    private void rebuildIssuedSetIfMissing(Long campaignId) {
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(issuedKey))) {
            return;
        }

        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);
        if (issuedUserIds == null || issuedUserIds.isEmpty()) {
            // DB에도 발급자가 없다 — 키를 새로 만들 필요가 없다(빈 Set은 Redis에 존재할 수 없다).
            return;
        }

        String[] userIdStrs = issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);
        redisTemplate.opsForSet().add(issuedKey, userIdStrs);
        log.info(
                "Rebuilt missing issued Set for campaignId: {} with {} users",
                campaignId,
                userIdStrs.length);
    }

    private List<String> verifyRecoveredStocks(List<CampaignStock> stocks) {
        List<String> keys =
                stocks.stream().map(stock -> String.format(KEY_STOCK, stock.getId())).toList();
        List<String> redisValues =
                keys.isEmpty() ? List.of() : redisTemplate.opsForValue().multiGet(keys);

        // 이 점검은 보조 안전장치일 뿐이다. multiGet이 예상과 다른 행 수를 반환하면(Redis 장애
        // 등) 이미 성공한 SETNX 복구까지 예외로 무너뜨리지 않고, 점검만 건너뛴다.
        if (redisValues == null || redisValues.size() != stocks.size()) {
            log.warn(
                    "Post-recovery consistency check skipped — multiGet returned {} rows for {}"
                            + " stocks",
                    redisValues == null ? "null" : redisValues.size(),
                    stocks.size());
            return List.of();
        }

        List<String> mismatches = new ArrayList<>();
        for (int index = 0; index < stocks.size(); index++) {
            CampaignStock stock = stocks.get(index);
            String redisValue = redisValues.get(index);
            int dbRemaining = stock.getRemainingStock();

            if (redisValue == null) {
                mismatches.add("stockId=" + stock.getId() + " redis=MISSING db=" + dbRemaining);
                continue;
            }

            try {
                int redisRemaining = Integer.parseInt(redisValue);
                if (redisRemaining > dbRemaining) {
                    mismatches.add(
                            "stockId="
                                    + stock.getId()
                                    + " redis="
                                    + redisRemaining
                                    + " db="
                                    + dbRemaining
                                    + " (redis > db, 초과 발급 위험)");
                }
            } catch (NumberFormatException e) {
                mismatches.add(
                        "stockId="
                                + stock.getId()
                                + " redis=INVALID("
                                + redisValue
                                + ") db="
                                + dbRemaining);
            }
        }
        return mismatches;
    }
}
