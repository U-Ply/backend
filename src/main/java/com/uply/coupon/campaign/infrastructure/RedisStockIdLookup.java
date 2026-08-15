package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.campaign.service.StockIdLookup;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 미리 적재된 Redis 캐시를 조회하여 조건(캠페인 ID, 노선 ID, 좌석 등급)에 매핑되는 Stock ID를 찾는 검색 도구
 * 
 * 쿠폰 발행 로직 전, stockId 조회 단계에서 발생할 수 있는 DB Connection Pool 고갈을 방지
 * 
 * @Primary - StockIdLookup 인터페이스 구현체가 여러 개 존재할 때, Spring DI 컨테이너가 최우선으로 주입하도록 지정
 * (우선 이렇게 설정해놓음)
 */
@Primary
@Component
@RequiredArgsConstructor
public class RedisStockIdLookup implements StockIdLookup {

	/** Redis에 적재된 Stock ID 매핑 Key 포맷 (stockId:{campaignId}:{routeId}:{fareClass}) */
    private static final String KEY_STOCK_ID = "stockId:%d:%s:%s";

    private final StringRedisTemplate redisTemplate;

    @Override
    public Long lookupStockId(Long campaignId, String routeId, String fareClass) {
        String key = String.format(KEY_STOCK_ID, campaignId, routeId, fareClass);
        String stockIdStr = redisTemplate.opsForValue().get(key);

        if (stockIdStr == null) {
            throw new CampaignNotFoundException(campaignId, routeId, fareClass);
        }

        return Long.parseLong(stockIdStr);
    }
}