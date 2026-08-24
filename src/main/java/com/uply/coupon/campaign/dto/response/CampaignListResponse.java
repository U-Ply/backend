package com.uply.coupon.campaign.dto.response;

import com.uply.coupon.campaign.domain.Campaign;
import java.time.LocalDateTime;
import java.util.List;

/** 캠페인 목록 조회 응답 DTO */
public record CampaignListResponse(List<CampaignSummaryResponse> campaigns) {

    public CampaignListResponse {
        campaigns = List.copyOf(campaigns);
    }

    public static CampaignListResponse of(List<Campaign> campaigns, LocalDateTime now) {
        List<CampaignSummaryResponse> items =
                campaigns.stream()
                        .map(campaign -> CampaignSummaryResponse.of(campaign, now))
                        .toList();
        return new CampaignListResponse(items);
    }
}
