package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final long CACHE_TTL_HOURS = 24;

    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 특정 캠페인의 잔여 재고 및 발급 이력을 RDB에서 조회하여 Redis 캐시에 적재/복구
     *
     * @param campaignId 사전 적재 및 복구 대상 캠페인 ID
     */
    @Transactional(readOnly = true)
    public void warmupCampaign(Long campaignId) {
        log.info("Starting cache warm-up and recovery for campaignId: {}", campaignId);

        // 1. 해당 캠페인의 전체 Stock 목록 DB 조회
        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
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
                        String.valueOf(openAtEpochMillis),
                        CACHE_TTL_HOURS,
                        TimeUnit.HOURS);

        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate
                .opsForValue()
                .set(
                        String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId),
                        String.valueOf(expireAtEpochMillis),
                        CACHE_TTL_HOURS,
                        TimeUnit.HOURS);

        // 3. stock:{stockId} 잔여 재고(remainingStock) 기준 캐싱
        for (CampaignStock stock : stocks) {
            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(KEY_STOCK, stock.getId()),
                            String.valueOf(stock.getRemainingStock()),
                            CACHE_TTL_HOURS,
                            TimeUnit.HOURS);

            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(
                                    KEY_STOCK_ID, campaignId, stock.getRouteId(), stock.getFareClass()),
                            String.valueOf(stock.getId()),
                            CACHE_TTL_HOURS,
                            TimeUnit.HOURS);
        }

        // 4. issued:{campaignId} Set 원자적 재구축 (RENAME 활용)
        rebuildIssuedSet(campaignId);

        log.info(
                "Cache warm-up and recovery completed successfully for campaignId: {}", campaignId);
    }

    private void rebuildIssuedSet(Long campaignId) {
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        String tempIssuedKey = String.format(KEY_TEMP_ISSUED, campaignId);

        // 이전 미완료 작업으로 잔존할 수 있는 임시 키 제거
        redisTemplate.delete(tempIssuedKey);

        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);

        if (issuedUserIds != null && !issuedUserIds.isEmpty()) {
            String[] userIdStrs =
                    issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);

            // 임시 Set에 DB 기준 발급 유저 적재
            redisTemplate.opsForSet().add(tempIssuedKey, userIdStrs);
            redisTemplate.expire(tempIssuedKey, CACHE_TTL_HOURS, TimeUnit.HOURS);

            // RENAME을 통한 원자적 Key 교체 (기존 오염 데이터 즉시 대체)
            redisTemplate.rename(tempIssuedKey, issuedKey);
            log.info(
                    "Atomically replaced issued Set for campaignId: {} with {} users",
                    campaignId,
                    userIdStrs.length);
        } else {
            // DB에 발급 이력이 완전히 없는 경우 오염된 기존 Set 완전 삭제
            redisTemplate.delete(issuedKey);
            log.info("Cleared issued Set for campaignId: {} (0 users in DB)", campaignId);
        }
    }
}