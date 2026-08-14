package com.uply.coupon.campaign.service;

/** CampaignStock 에서 stock_id만 조회하는 기능 */
public interface StockIdLookup {
    Long lookupStockId(Long campaignId, String routeId, String fareClass);
}
