package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
     * <p><b>실행 전제</b> — 이 메서드는 DB를 정답으로 보고 Redis를 통째로 덮어쓴다. 따라서 발급 요청을 차단하고 Kafka lag이 0인 것(=발급
     * 이벤트가 모두 DB에 반영된 것)을 확인한 뒤에 호출해야 한다. lag이 남은 상태로 호출하면 아직 DB에 없는 발급분이 issued Set에서 사라져 같은 유저가
     * 다시 발급받을 수 있고, 재고도 그만큼 부풀려 복구된다.
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

        if (issuedUserIds == null || issuedUserIds.isEmpty()) {
            // DB에 발급자가 없으면 Set도 비어 있어야 한다.
            // SADD만 하던 기존 방식은 이 경우 아무 일도 하지 않아, Redis에만 남아 있던
            // 오염된 유저가 "복구" 후에도 그대로 살아남았다.
            redisTemplate.delete(issuedKey);
            log.info("Cleared issued Set for campaignId: {} (DB에 발급 이력 없음)", campaignId);
        } else {
            String[] userIdStrs =
                    issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);

            // 임시 키에 DB 기준으로 새로 쌓은 뒤 RENAME으로 원자 교체한다.
            // 기존 키에 SADD로 덧칠하면 DB에 없는 유저가 Set에 남아 발급이 부당하게 거부된다.
            // RENAME은 대상 키를 덮어쓰면서 TTL까지 함께 옮기므로 별도 expire가 필요 없다.
            String rebuildKey = issuedKey + ":rebuild:" + UUID.randomUUID();
            redisTemplate.opsForSet().add(rebuildKey, userIdStrs);
            redisTemplate.expire(rebuildKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.rename(rebuildKey, issuedKey);

            log.info(
                    "Rebuilt issued Set for campaignId: {} with {} users",
                    campaignId,
                    userIdStrs.length);
        }

        log.info(
                "Cache warm-up and recovery completed successfully for campaignId: {}", campaignId);
    }
}
