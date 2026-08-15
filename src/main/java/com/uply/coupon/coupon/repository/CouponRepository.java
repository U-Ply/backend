package com.uply.coupon.coupon.repository;

import com.uply.coupon.coupon.domain.Coupon;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    boolean existsByCampaignIdAndUserId(Long campaignId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Coupon c
               set c.status = com.uply.coupon.coupon.domain.CouponStatus.USED,
                   c.usedAt = CURRENT_TIMESTAMP
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt > CURRENT_TIMESTAMP
            """)
    int useIfIssued(@Param("couponId") Long couponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Coupon c
               set c.status = com.uply.coupon.coupon.domain.CouponStatus.CANCELLED,
                   c.cancelledAt = CURRENT_TIMESTAMP
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt > CURRENT_TIMESTAMP
            """)
    int cancelIfIssued(@Param("couponId") Long couponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Coupon c
               set c.status = com.uply.coupon.coupon.domain.CouponStatus.EXPIRED,
                   c.expiredAt = :cutoff
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt <= :cutoff
            """)
    int expireIfIssued(@Param("couponId") Long couponId, @Param("cutoff") LocalDateTime cutoff);
}
