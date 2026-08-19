package com.uply.coupon.campaign.repository;

import java.time.Instant;

/**
 * Redis 에서 캠페인의 openAt, expireAt 을 조회하는 역할
 */
public interface CampaignCacheRepository {
    Instant getOpenAt(Long campaignId);
    Instant getExpireAt(Long campaignId);
}