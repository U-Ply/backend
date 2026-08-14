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
                   c.usedAt = :usedAt
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt > :usedAt
            """)
    int useIfIssued(@Param("couponId") Long couponId, @Param("usedAt") LocalDateTime usedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Coupon c
               set c.status = com.uply.coupon.coupon.domain.CouponStatus.CANCELLED,
                   c.cancelledAt = :cancelledAt
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt > :cancelledAt
            """)
    int cancelIfIssued(
            @Param("couponId") Long couponId, @Param("cancelledAt") LocalDateTime cancelledAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Coupon c
               set c.status = com.uply.coupon.coupon.domain.CouponStatus.EXPIRED,
                   c.expiredAt = :expiredAt
             where c.couponId = :couponId
               and c.status = com.uply.coupon.coupon.domain.CouponStatus.ISSUED
               and c.expireAt <= :expiredAt
            """)
    int expireIfIssued(
            @Param("couponId") Long couponId, @Param("expiredAt") LocalDateTime expiredAt);
}
