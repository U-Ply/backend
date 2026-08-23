package com.uply.coupon.campaign.dto.response;

/** 노선·좌석 등급별 발급 현황 조회 응답 DTO. remainingStock은 Redis 값을 그대로 사용한다. */
public record CampaignStatusResponse(
        Long campaignId,
        String routeId,
        String fareClass,
        Integer totalStock,
        Integer remainingStock) {}
