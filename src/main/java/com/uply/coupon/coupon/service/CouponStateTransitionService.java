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
    public void use(Long couponId, String idempotencyKey, LocalDateTime usedAt) {
        int updatedRows = couponRepository.useIfIssued(couponId, usedAt);
        validateUpdatedRows(updatedRows, couponId, CouponStatus.USED);

        couponHistoryRepository.save(CouponHistory.used(couponId, idempotencyKey, usedAt));
    }

    @Transactional
    public void cancel(Long couponId, String idempotencyKey, LocalDateTime cancelledAt) {
        int updatedRows = couponRepository.cancelIfIssued(couponId, cancelledAt);
        validateUpdatedRows(updatedRows, couponId, CouponStatus.CANCELLED);

        couponHistoryRepository.save(
                CouponHistory.cancelled(couponId, idempotencyKey, cancelledAt));
    }

    @Transactional
    public void expire(Long couponId, String idempotencyKey, LocalDateTime expiredAt) {
        int updatedRows = couponRepository.expireIfIssued(couponId, expiredAt);
        validateUpdatedRows(updatedRows, couponId, CouponStatus.EXPIRED);

        couponHistoryRepository.save(CouponHistory.expired(couponId, idempotencyKey, expiredAt));
    }

    private void validateUpdatedRows(int updatedRows, Long couponId, CouponStatus targetStatus) {
        if (updatedRows == 1) {
            return;
        }

        Coupon coupon =
                couponRepository
                        .findById(couponId)
                        .orElseThrow(() -> new CouponNotFoundException(couponId));
        throw new InvalidStateTransitionException(coupon.getStatus(), targetStatus);
    }
}
