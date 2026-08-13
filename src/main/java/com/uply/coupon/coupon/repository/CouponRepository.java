package com.uply.coupon.coupon.repository;

import com.uply.coupon.coupon.domain.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    boolean existsByCampaignIdAndUserId(Long campaignId, Long userId);
}
