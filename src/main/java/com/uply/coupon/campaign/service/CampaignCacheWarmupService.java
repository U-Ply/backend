package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
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
 *   3. issued:{campaignId} (Set) - 중복 발급 방지용 유저 집합
 * <pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignCacheWarmupService {

    private static final String KEY_STOCK = "stock:%d";
    private static final String KEY_STOCK_ID = "stockId:%d:%s:%s";
    private static final String KEY_ISSUED = "issued:%d";

    private static final long CACHE_TTL_HOURS = 24;

    private final CampaignStockRepository campaignStockRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * 특정 캠페인의 재고 목록을 RDB에서 조회하여 Redis 캐시에 사전 적재
     *
     * @param campaignId 사전 적재 대상 캠페인 ID
     */
    @Transactional(readOnly = true)
    public void warmupCampaign(Long campaignId) {
        log.info("Starting cache warm-up for campaignId: {}", campaignId);

        // 1. 해당 캠페인의 전체 Stock 목록 DB 조회
        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
            log.warn("No stocks found for campaignId: {}", campaignId);
            return;
        }

        for (CampaignStock stock : stocks) {
            // 1) stock:{stockId} -> 재고 수량
            String stockKey = String.format(KEY_STOCK, stock.getId());
            redisTemplate
                    .opsForValue()
                    .set(
                            stockKey,
                            String.valueOf(stock.getTotalStock()),
                            CACHE_TTL_HOURS,
                            TimeUnit.HOURS);

            // 2) stockId:{campaignId}:{routeId}:{fareClass} -> stockId
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

        // 3) issued:{campaignId} -> userId Set (TTL만 먼저 설정하여 자료구조 생성 준비)
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        redisTemplate.expire(issuedKey, CACHE_TTL_HOURS, TimeUnit.HOURS);

        log.info("Cache warm-up completed successfully for campaignId: {}", campaignId);
    }
}
