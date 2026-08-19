package com.uply.coupon.coupon.service;

import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignCouponRevokeService {

    private static final String HISTORY_KEY_PREFIX = "revoke-";

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional
    public int revoke(Long campaignId, String idempotencyKey) {
        List<Long> couponIds = couponRepository.findIssuedCouponIdsByCampaignId(campaignId);
        int revokedCount = 0;

        for (Long couponId : couponIds) {
            int updatedRows = couponRepository.revokeIfIssued(couponId);
            if (updatedRows == 0) {
                continue;
            }

            Coupon coupon =
                    couponRepository
                            .findById(couponId)
                            .orElseThrow(() -> new CouponNotFoundException(couponId));
            couponHistoryRepository.save(
                    CouponHistory.revoked(
                            couponId,
                            createHistoryIdempotencyKey(couponId, idempotencyKey),
                            coupon.getCancelledAt()));
            revokedCount++;
        }

        return revokedCount;
    }

    private String createHistoryIdempotencyKey(Long couponId, String idempotencyKey) {
        return HISTORY_KEY_PREFIX + couponId + "-" + idempotencyKey;
    }
}
