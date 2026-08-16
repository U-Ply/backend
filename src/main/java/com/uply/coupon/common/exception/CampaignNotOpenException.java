package com.uply.coupon.common.exception;

import java.time.LocalDateTime;

public class CampaignNotOpenException extends RuntimeException {

    public CampaignNotOpenException(Long campaignId, LocalDateTime openAt) {
        super("Campaign is not open: campaignId=" + campaignId + ", openAt=" + openAt);
    }
}
