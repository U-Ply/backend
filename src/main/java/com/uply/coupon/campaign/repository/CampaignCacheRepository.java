package com.uply.coupon.campaign.repository;

import java.time.Instant;

/** Redis 에서 캠페인의 openAt, expireAt, 재고 풀의 잔여 재고를 조회하는 역할 */
public interface CampaignCacheRepository {
    Instant getOpenAt(Long campaignId);

    Instant getExpireAt(Long campaignId);

    Integer getRemainingStock(Long stockId);
}
