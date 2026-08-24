package com.uply.coupon.campaign.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 캠페인 목록 조회의 캠페인 항목 응답 DTO */
public record CampaignSummaryResponse(
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
        CampaignStatus status) {

    public static CampaignSummaryResponse of(Campaign campaign, LocalDateTime now) {
        CampaignStatus status =
                CampaignStatus.of(now, campaign.getOpenAt(), campaign.getExpireAt());
        return new CampaignSummaryResponse(
                campaign.getId(),
                campaign.getName(),
                toInstant(campaign.getOpenAt()),
                toInstant(campaign.getExpireAt()),
                status);
    }

    static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
