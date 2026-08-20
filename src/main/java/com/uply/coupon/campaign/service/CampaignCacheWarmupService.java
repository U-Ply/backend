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
 * 이벤트 개시 전 RDB의 캠페인 재고 데이터를 Redis 캐시에 사전 적재(Warm-up)하는 서비스
 *
 * 이벤트 오픈 시점에 발생하는 대량의 읽기/쓰기 트래픽이 RDB로 직접 몰리는 Cache Stampede 및 DB Connection Pool 고갈 현상을 방지
 *
 * <pre>
 * 주요 캐싱 데이터 구조
 *   1. stock:{stockId} (String) - 잔여/총 재고 수량
 *   2. stockId:{campaignId}:{routeId}:{fareClass} (String) - 검색 조건별 매핑되는 Stock PK
 *   3. campaign:{campaignId}:openAt (String) - 오픈 시각(UTC epoch milliseconds)
 *   4. issued:{campaignId} (Set) - 중복 발급 방지용 유저 집합
 *   5. campaign:{campaignId}:expireAt (String) - 만료 시각
 * <pre>
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

    private static final long CACHE_TTL_HOURS = 24;

    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository; // [추가] 기발급 유저 조회를 위한 Repository
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
        String campaignOpenAtKey = String.format(KEY_CAMPAIGN_OPEN_AT, campaignId);
        redisTemplate
                .opsForValue()
                .set(
                        campaignOpenAtKey,
                        String.valueOf(openAtEpochMillis),
                        CACHE_TTL_HOURS,
                        TimeUnit.HOURS);

        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        String campaignExpireAtKey = String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId);
        redisTemplate
                .opsForValue()
                .set(
                        campaignExpireAtKey,
                        String.valueOf(expireAtEpochMillis),
                        CACHE_TTL_HOURS,
                        TimeUnit.HOURS);

        // 3. stock:{stockId} 잔여 재고(remainingStock) 기준 캐싱
        for (CampaignStock stock : stocks) {
            String stockKey = String.format(KEY_STOCK, stock.getId());

            // [수정] getTotalStock() -> getRemainingStock() 으로 변경하여 실제 잔여 재고 복구
            redisTemplate
                    .opsForValue()
                    .set(
                            stockKey,
                            String.valueOf(stock.getRemainingStock()),
                            CACHE_TTL_HOURS,
                            TimeUnit.HOURS);

            String stockIdKey =
                    String.format(
                            KEY_STOCK_ID, campaignId, stock.getRouteId(), stock.getFareClass());
            redisTemplate
                    .opsForValue()
                    .set(
                            stockIdKey,
                            String.valueOf(stock.getId()),
                            CACHE_TTL_HOURS,
                            TimeUnit.HOURS);
        }

        // 4. issued:{campaignId} Set 재구축 (RDB 발급 이력 동기화)
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);

        if (issuedUserIds != null && !issuedUserIds.isEmpty()) {
            String[] userIdStrs =
                    issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);

            // DB의 기발급 유저 목록을 SADD로 Redis Set에 복구
            redisTemplate.opsForSet().add(issuedKey, userIdStrs);
            log.info(
                    "Rebuilt issued Set for campaignId: {} with {} users",
                    campaignId,
                    userIdStrs.length);
        }

        redisTemplate.expire(issuedKey, CACHE_TTL_HOURS, TimeUnit.HOURS);

        log.info(
                "Cache warm-up and recovery completed successfully for campaignId: {}", campaignId);
    }
}
