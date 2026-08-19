package com.uply.coupon.coupon.dto.response;

/** 항공사 미사용 쿠폰 일괄 취소 응답 DTO */
public record CampaignCouponRevokeResponse(Long campaignId, int revokedCount) {

    public static CampaignCouponRevokeResponse of(Long campaignId, int revokedCount) {
        return new CampaignCouponRevokeResponse(campaignId, revokedCount);
    }
}
