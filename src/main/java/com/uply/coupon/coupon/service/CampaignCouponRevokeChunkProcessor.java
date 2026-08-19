package com.uply.coupon.coupon.service;

import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 이 클래스를 별도 Spring Bean으로 분리한 이유도 REQUIRES_NEW를 정상 적용하기 위해서인데 이게 무슨 소리냐면
// 같은 클래스 안에서 자기 메서드를 직접 호출하면 Spring 트랜잭션 프록시가 적용되지 않을 수도 있기 때문입니다.
@Service
@RequiredArgsConstructor
public class CampaignCouponRevokeChunkProcessor {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeChunk(List<Long> couponIds, String historyKeyPrefix) {
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
                            couponId, historyKeyPrefix + couponId, coupon.getCancelledAt()));
            revokedCount++;
        }

        return revokedCount;
    }
}
