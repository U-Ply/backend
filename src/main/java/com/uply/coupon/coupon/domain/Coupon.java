package com.uply.coupon.coupon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
// 같은 campaign에 같은 user가 두번 발급 차단
@Table(
        name = "coupons",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon implements Persistable<Long> {

    @Id private Long couponId;

    private Long userId;
    private Long campaignId;
    private Long stockId;

    @Enumerated(EnumType.STRING) // DB에 숫자(0, 1, 2)로 상태 저장이 아닌 문자열(ISSUED, USED)등 으로 저장
    private CouponStatus status;

    private LocalDateTime issuedAt;
    private LocalDateTime expireAt;
    private LocalDateTime cancelledAt;

    @Transient private boolean newEntity = true;

    public static Coupon issue(
            Long couponId, Long userId, Long campaignId, Long stockId, LocalDateTime expireAt) {
        Coupon coupon = new Coupon();
        coupon.couponId = couponId;
        coupon.userId = userId;
        coupon.campaignId = campaignId;
        coupon.stockId = stockId;
        coupon.status = CouponStatus.ISSUED;
        coupon.issuedAt = LocalDateTime.now();
        coupon.expireAt = expireAt;

        return coupon;
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
