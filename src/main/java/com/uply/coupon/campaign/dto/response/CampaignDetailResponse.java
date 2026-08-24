package com.uply.coupon.campaign.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 캠페인 기본 정보 조회 응답 DTO */
public record CampaignDetailResponse(
        Long campaignId,
        String name,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant openAt,
        @JsonFormat(
                        shape = JsonFormat.Shape.STRING,
                        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        timezone = "UTC")
                Instant expireAt,
        CampaignStatus status,
        List<CampaignStockSummaryResponse> stocks) {

    public CampaignDetailResponse {
        stocks = List.copyOf(stocks);
    }

    public static CampaignDetailResponse of(
            Campaign campaign, LocalDateTime now, List<CampaignStockSummaryResponse> stocks) {
        CampaignStatus status =
                CampaignStatus.of(now, campaign.getOpenAt(), campaign.getExpireAt());
        return new CampaignDetailResponse(
                campaign.getId(),
                campaign.getName(),
                CampaignSummaryResponse.toInstant(campaign.getOpenAt()),
                CampaignSummaryResponse.toInstant(campaign.getExpireAt()),
                status,
                stocks);
    }
}
