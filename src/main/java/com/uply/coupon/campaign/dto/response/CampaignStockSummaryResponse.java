package com.uply.coupon.campaign.dto.response;

/** 캠페인 기본 정보 조회의 재고 풀 항목 응답 DTO. totalStock은 MySQL, remainingStock은 Redis 기준. */
public record CampaignStockSummaryResponse(
        String routeId, String fareClass, Integer totalStock, Integer remainingStock) {}
