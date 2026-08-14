package com.uply.coupon.campaign.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.uply.coupon.campaign.service.StockIdLookup;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisStockIdLookup implements StockIdLookup {

	private final StringRedisTemplate redisTemplate;
	
	@Override
	public Long lookupStockId(Long campaignId, String routeId, String fareClass) {
		String mapKey = String.format("stock-map:%d:%s:%s", campaignId, routeId, fareClass);
        String stockIdStr = redisTemplate.opsForValue().get(mapKey);

        if (stockIdStr == null) {
        	// 에러 필요
//            throw new CustomException(ErrorCode.STOCK_NOT_FOUND);
        }
        return Long.parseLong(stockIdStr);
	}

}
