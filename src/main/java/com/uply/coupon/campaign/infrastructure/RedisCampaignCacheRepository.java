package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisCampaignCacheRepository implements CampaignCacheRepository {

    private static final String KEY_CAMPAIGN_OPEN_AT = "campaign:%d:openAt";
    private static final String KEY_CAMPAIGN_EXPIRE_AT = "campaign:%d:expireAt";
    private static final String KEY_STOCK = "stock:%d";

    private final StringRedisTemplate redisTemplate;

    @Override
    public Instant getOpenAt(Long campaignId) {
        String key = String.format(KEY_CAMPAIGN_OPEN_AT, campaignId);
        return fetchInstantByKey(key, campaignId);
    }

    @Override
    public Instant getExpireAt(Long campaignId) {
        String key = String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId);
        return fetchInstantByKey(key, campaignId);
    }

    @Override
    public Integer getRemainingStock(Long stockId) {
        String key = String.format(KEY_STOCK, stockId);
        String valueStr = redisTemplate.opsForValue().get(key);

        // 재고 조회 실패를 0장으로 간주하면 "재고 소진"과 "캐시 미준비"를 구분할 수 없다.
        // 키가 아예 없는 경우는 캐시 미스 전용 예외로 구분해, 잘못된 값이 든 경우(시스템 오류)와
        // 다르게 취급한다 — 잘못된 값을 캐시 미스로 보고 자동 웜업하면 데이터 오염을 덮어버릴 수 있다.
        if (valueStr == null) {
            throw new CampaignStockCacheMissException(stockId);
        }

        try {
            return Integer.parseInt(valueStr);
        } catch (NumberFormatException e) {
            log.error("[재고 파싱 오류] key: {}, value: {}", key, valueStr, e);
            throw new IllegalStateException(
                    "Invalid remaining stock cache value: stockId=" + stockId, e);
        }
    }

    private Instant fetchInstantByKey(String key, Long campaignId) {
        String valueStr = redisTemplate.opsForValue().get(key);

        if (valueStr == null) {
            throw new CampaignNotFoundException(campaignId);
        }

        try {
            long epochMillis = Long.parseLong(valueStr);
            return Instant.ofEpochMilli(epochMillis);
        } catch (NumberFormatException e) {
            log.error("[시각 파싱 오류] key: {}, value: {}", key, valueStr, e);
            throw new CouponIssueException(IssueFailReason.SYSTEM_ERROR, e);
        }
    }
}
