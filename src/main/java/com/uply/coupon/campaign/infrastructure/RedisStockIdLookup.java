package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.service.StockIdLookup;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 미리 적재된 Redis 캐시를 조회하여 조건(캠페인 ID, 노선 ID, 좌석 등급)에 매핑되는 Stock ID를 찾는 검색 도구
 *
 * <p>쿠폰 발행 로직 전, stockId 조회 단계에서 발생할 수 있는 DB Connection Pool 고갈을 방지한다. Redis Lua 전략에서만 사용한다.
 */
@Component
@RequiredArgsConstructor
public class RedisStockIdLookup implements StockIdLookup {

    /** Redis에 적재된 Stock ID 매핑 Key 포맷 (stockId:{campaignId}:{routeId}:{fareClass}) */
    private static final String KEY_STOCK_ID = "stockId:%d:%s:%s";

    private final CampaignStockRepository campaignStockRepository;

    private final StringRedisTemplate redisTemplate;

    @Override
    public Long lookupStockId(Long campaignId, String routeId, String fareClass) {
        String key = String.format(KEY_STOCK_ID, campaignId, routeId, fareClass);
        String stockIdStr = redisTemplate.opsForValue().get(key);

        if (stockIdStr == null) {
            // DB에는 있는데 Redis 조회 실패 -> 503 CAMPAIGN_NOT_CACHED 을 줘야한다.
            if (campaignStockRepository.existsByCampaignIdAndRouteIdAndFareClass(
                    campaignId, routeId, fareClass)) {
                throw new CouponIssueException(IssueFailReason.CAMPAIGN_NOT_CACHED);
            }
            // DB에도 없으면 CampaignNotFoundException(404)
            throw new CampaignNotFoundException(campaignId, routeId, fareClass);
        }

        return Long.parseLong(stockIdStr);
    }
}
