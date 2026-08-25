package com.uply.coupon.campaign.dto.response;

import java.util.List;

/** 캠페인 Redis 캐시 웜업/복구 응답 DTO */
public record CampaignCacheWarmupResponse(Long campaignId, String status, List<String> mismatches) {

    public static CampaignCacheWarmupResponse completed(Long campaignId) {
        return new CampaignCacheWarmupResponse(campaignId, "WARMED_UP", List.of());
    }

    /**
     * mismatches는 recoverMissingCache가 복구 직후 수행한 자체 점검(이 캠페인의 재고풀만 대상) 결과다. 비어 있지 않으면 SETNX가 손대지 않은
     * "이미 있던" 키에 실제로는 잘못된 값이 들어있었다는 뜻이며, 200 응답이라도 호출자가 이 값을 확인해야 한다.
     */
    public static CampaignCacheWarmupResponse recovered(Long campaignId, List<String> mismatches) {
        String status = mismatches.isEmpty() ? "RECOVERED" : "RECOVERED_WITH_MISMATCH";
        return new CampaignCacheWarmupResponse(campaignId, status, mismatches);
    }
}
