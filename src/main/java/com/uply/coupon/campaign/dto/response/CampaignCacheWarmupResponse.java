package com.uply.coupon.campaign.dto.response;

/** 캠페인 Redis 캐시 웜업/복구 응답 DTO */
public record CampaignCacheWarmupResponse(Long campaignId, String status) {

    public static CampaignCacheWarmupResponse completed(Long campaignId) {
        return new CampaignCacheWarmupResponse(campaignId, "WARMED_UP");
    }
}
