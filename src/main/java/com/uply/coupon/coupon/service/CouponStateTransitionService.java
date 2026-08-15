package com.uply.coupon.coupon.service;

import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponStateTransitionService {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional
    public void use(Long couponId, String idempotencyKey) {
        int updatedRows = couponRepository.useIfIssued(couponId);
        validateUpdatedRows(updatedRows, couponId, CouponStatus.USED);

        Coupon coupon = findCoupon(couponId);
        couponHistoryRepository.save(
                CouponHistory.used(couponId, idempotencyKey, coupon.getUsedAt()));
    }

    @Transactional
    public void cancel(Long couponId, String idempotencyKey) {
        int updatedRows = couponRepository.cancelIfIssued(couponId);
        validateUpdatedRows(updatedRows, couponId, CouponStatus.CANCELLED);

        Coupon coupon = findCoupon(couponId);
        couponHistoryRepository.save(
                CouponHistory.cancelled(couponId, idempotencyKey, coupon.getCancelledAt()));
    }

    @Transactional
    public boolean expireCoupon(Long couponId, String idempotencyKey, LocalDateTime cutoff) {
        int updatedRows = couponRepository.expireIfIssued(couponId, cutoff);
        if (updatedRows == 0) {
            return false;
        }

        couponHistoryRepository.save(CouponHistory.expired(couponId, idempotencyKey, cutoff));
        return true;
    }

    private void validateUpdatedRows(int updatedRows, Long couponId, CouponStatus targetStatus) {
        if (updatedRows == 1) {
            return;
        }

        Coupon coupon = findCoupon(couponId);
        throw new InvalidStateTransitionException(coupon.getStatus(), targetStatus);
    }

    private Coupon findCoupon(Long couponId) {
        return couponRepository
                .findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(couponId));
    }
}
