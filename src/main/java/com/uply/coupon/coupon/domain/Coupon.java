package com.uply.coupon.coupon.domain;

import com.uply.coupon.common.exception.InvalidStateTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

@Entity
// 같은 campaign에 같은 user가 두번 발급 차단
@Table(
        name = "coupons",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "user_id"}),
        indexes = {
            @Index(name = "idx_coupon_stock_status", columnList = "stock_id, status"),
            @Index(name = "idx_coupon_user", columnList = "user_id"),
            @Index(name = "idx_coupon_expire", columnList = "status, expire_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon implements Persistable<Long> {

    @Id private Long couponId;

    private Long userId;
    private Long campaignId;
    private Long stockId;

    @Enumerated(EnumType.STRING)
    private CouponStatus status;

    private LocalDateTime issuedAt;
    private LocalDateTime usedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime expiredAt;
    private LocalDateTime expireAt;

    @CreationTimestamp private LocalDateTime createdAt;

    @Transient private boolean newEntity = true;

    /**
     * 발급 시각을 호출부가 반드시 명시하도록 인자로 받는다.
     *
     * <p>JVM 시각으로 채우는 오버로드는 두지 않는다. 기준 시간은 DB 서버 시간이며(DB 전략은 NOW(3), Lua 전략은 Redis TIME), 내부에서
     * LocalDateTime.now()를 부르면 그 기준이 조용히 깨진다.
     */
    public static Coupon issue(
            Long couponId,
            Long userId,
            Long campaignId,
            Long stockId,
            LocalDateTime issuedAt,
            LocalDateTime expireAt) {
        Coupon coupon = new Coupon();
        coupon.couponId = couponId;
        coupon.userId = userId;
        coupon.campaignId = campaignId;
        coupon.stockId = stockId;
        coupon.status = CouponStatus.ISSUED;
        coupon.issuedAt = issuedAt;
        coupon.expireAt = expireAt;

        return coupon;
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

    private void validateTransition(CouponStatus targetStatus) {
        boolean allowed =
                switch (status) {
                    case ISSUED ->
                            targetStatus == CouponStatus.USED
                                    || targetStatus == CouponStatus.CANCELLED
                                    || targetStatus == CouponStatus.EXPIRED;
                    case USED -> targetStatus == CouponStatus.CANCELLED;
                    case CANCELLED, EXPIRED -> false;
                };

        if (!allowed) {
            throw new InvalidStateTransitionException(status, targetStatus);
        }
    }

    @Override
    public Long getId() {
        return couponId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
