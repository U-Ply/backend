package com.uply.coupon.coupon.strategy.save;

import java.time.LocalDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "sync-db")
@RequiredArgsConstructor
public class SyncMysqlSaveStrategy implements CouponSaveStrategy {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
	
	@Override
	@Transactional
	public void save(Long couponId, Long userId, Long campaignId, Long stockId, String idempotencyKey) {
		Coupon coupon =
                Coupon.issue(
                		couponId,
                        userId,
                        campaignId,
                        stockId,
                        LocalDateTime.now().plusDays(7));
        couponRepository.save(coupon);
        couponHistoryRepository.save(CouponHistory.issued(coupon.getCouponId(), idempotencyKey));
	}

}
