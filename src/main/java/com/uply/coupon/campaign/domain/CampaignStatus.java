package com.uply.coupon.campaign.domain;

import java.time.LocalDateTime;

/** 조회 시점의 DB 서버 시각을 openAt/expireAt과 비교해 판정하는 캠페인 상태. DB 컬럼으로 저장하지 않는다. */
public enum CampaignStatus {
    SCHEDULED,
    OPEN,
    CLOSED;

    public static CampaignStatus of(
            LocalDateTime now, LocalDateTime openAt, LocalDateTime expireAt) {
        if (now.isBefore(openAt)) {
            return SCHEDULED;
        }
        if (now.isBefore(expireAt)) {
            return OPEN;
        }
        return CLOSED;
    }
}
