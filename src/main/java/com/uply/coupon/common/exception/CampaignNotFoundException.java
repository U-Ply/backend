package com.uply.coupon.common.exception;

public class CampaignNotFoundException extends RuntimeException {

    public CampaignNotFoundException(Long campaignId) {
        super("Campaign not found: campaignId=" + campaignId);
    }

    public CampaignNotFoundException(Long campaignId, String routeId, String fareClass) {
        super(
                "Campaign stock not found: campaignId="
                        + campaignId
                        + ", routeId="
                        + routeId
                        + ", fareClass="
                        + fareClass);
    }

    public CampaignNotFoundException(Long campaignId, Long stockId) {
        super("Campaign stock not found: campaignId=" + campaignId + ", stockId=" + stockId);
    }
}
