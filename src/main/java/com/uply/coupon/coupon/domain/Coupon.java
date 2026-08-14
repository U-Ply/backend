package com.uply.coupon.coupon.domain;

import com.uply.coupon.common.exception.InvalidStateTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
// 같은 campaign에 같은 user가 두번 발급 차단
@Table(
        name = "coupons",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "user_id"}),
        indexes = {
            @Index(name = "idx_coupons_stock_status", columnList = "stock_id, status"),
            @Index(name = "idx_coupons_user", columnList = "user_id"),
            @Index(name = "idx_coupons_status_expire_at", columnList = "status, expire_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long couponId;

    private Long userId;
    private Long campaignId; // 이 쿠폰이 어느 캠페인에서 발급되었는지
    private Long stockId; // 이 쿠폰이 어느 재고 풀에서 차감되어 발급됐는지

    @Enumerated(EnumType.STRING) // DB에 숫자(0, 1, 2)로 상태 저장이 아닌 문자열(ISSUED, USED)등 으로 저장
    private CouponStatus status;

    private LocalDateTime issuedAt; // 쿠폰이 발급된 시각
    private LocalDateTime usedAt;
    private LocalDateTime expireAt; // 쿠폰을 언제까지 사용할 수 있는지 나타내는 유효기간
    private LocalDateTime cancelledAt;
    private LocalDateTime expiredAt; // 쿠폰을 실제로 EXPIRED 상태로 변경한 시각

    @CreationTimestamp private LocalDateTime createdAt; // 쿠폰 행이 DB에 처음 저장된 시각

    public static Coupon issue(Long userId, Long campaignId, Long stockId, LocalDateTime expireAt) {
        Coupon coupon = new Coupon();
        coupon.userId = userId;
        coupon.campaignId = campaignId;
        coupon.stockId = stockId;
        coupon.status = CouponStatus.ISSUED;
        coupon.issuedAt = LocalDateTime.now();
        coupon.expireAt = expireAt;

        return coupon;
    }

    private void validateTransition(CouponStatus targetStatus) {
        if (status != CouponStatus.ISSUED) {
            throw new InvalidStateTransitionException(status, targetStatus);
        }
    }

    public void use(LocalDateTime usedAt) {
        validateTransition(CouponStatus.USED);
        this.status = CouponStatus.USED;
        this.usedAt = usedAt;
    }

    public void cancel(LocalDateTime cancelledAt) {
        validateTransition(CouponStatus.CANCELLED);
        this.status = CouponStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }

    public void expire(LocalDateTime expiredAt) {
        validateTransition(CouponStatus.EXPIRED);
        this.status = CouponStatus.EXPIRED;
        this.expiredAt = expiredAt;
    }
}
