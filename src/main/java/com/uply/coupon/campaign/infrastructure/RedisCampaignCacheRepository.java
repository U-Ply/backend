package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.campaign.repository.CampaignCacheRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
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

    private Instant fetchInstantByKey(String key, Long campaignId) {
        String valueStr = redisTemplate.opsForValue().get(key);

        if (valueStr == null) {
            throw new CampaignNotFoundException(campaignId, campaignId);
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