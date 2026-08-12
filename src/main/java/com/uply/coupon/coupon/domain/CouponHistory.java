package com.uply.coupon.coupon.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 쿠폰 상태(발급/사용/취소/만료) 이력 로그
@Entity
// 같은 idempotencyKey로 재시도 요청 시 저장을 실패하게 하는 멱등성 보장하기
@Table(
        name = "coupon_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    private Long couponId;

    @Enumerated(EnumType.STRING)
    private CouponStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private CouponStatus toStatus;

    private String idempotencyKey;
    private LocalDateTime eventAt;

    // 발급 이력 기록
    public static CouponHistory issued(Long couponId, String idempotencyKey) {
        CouponHistory history = new CouponHistory();
        history.couponId = couponId;
        history.fromStatus = null;
        history.toStatus = CouponStatus.ISSUED;
        history.idempotencyKey = idempotencyKey;
        history.eventAt = LocalDateTime.now();
        return history;
    }
}
