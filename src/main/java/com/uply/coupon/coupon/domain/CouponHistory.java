package com.uply.coupon.coupon.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 쿠폰 상태(발급/사용/취소/만료) 이력 로그
@Entity
// 같은 idempotencyKey로 재시도 요청 시 저장을 실패하게 하는 멱등성 보장하기
@Table(
        name = "coupon_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"idempotency_key"}),
        indexes = {
            // 특정 쿠폰의 이력을 발생 시각 순서로 조회할 때 사용
            @Index(name = "idx_coupon_event", columnList = "coupon_id, event_at, history_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long historyId;

    private Long couponId; // 이 이력이 어느 쿠폰의 것인지 나타냅니다.

    @Enumerated(EnumType.STRING)
    private CouponStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private CouponStatus toStatus;

    private String idempotencyKey;
    private LocalDateTime eventAt; // 쿠폰 상태 변경 사건이 실제로 발생한 시각

    @CreationTimestamp private LocalDateTime createdAt; // 이 이력 엔티티가 저장될 때 Hibernate가 시간을 자동으로 채움

    // 발급 이력 기록
    public static CouponHistory issued(Long couponId, String idempotencyKey) {
        return issued(couponId, idempotencyKey, LocalDateTime.now());
    }

    public static CouponHistory issued(
            Long couponId, String idempotencyKey, LocalDateTime eventAt) {
        CouponHistory history = new CouponHistory();
        history.couponId = couponId;
        history.fromStatus = null;
        history.toStatus = CouponStatus.ISSUED;
        history.idempotencyKey = idempotencyKey;
        history.eventAt = eventAt;
        return history;
    }

    // 사용 이력 기록
    public static CouponHistory used(Long couponId, String idempotencyKey, LocalDateTime eventAt) {
        return transitioned(
                couponId, CouponStatus.ISSUED, CouponStatus.USED, idempotencyKey, eventAt);
    }

    // 취소 이력 기록
    public static CouponHistory cancelled(
            Long couponId, String idempotencyKey, LocalDateTime eventAt) {
        return transitioned(
                couponId, CouponStatus.USED, CouponStatus.CANCELLED, idempotencyKey, eventAt);
    }

    // 만료 이력 기록
    public static CouponHistory expired(
            Long couponId, String idempotencyKey, LocalDateTime eventAt) {
        return transitioned(
                couponId, CouponStatus.ISSUED, CouponStatus.EXPIRED, idempotencyKey, eventAt);
    }

    // 발급 이후 상태 변경 이력의 공통 값 설정
    private static CouponHistory transitioned(
            Long couponId,
            CouponStatus fromStatus,
            CouponStatus toStatus,
            String idempotencyKey,
            LocalDateTime eventAt) {
        CouponHistory history = new CouponHistory();
        history.couponId = couponId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.idempotencyKey = idempotencyKey;
        history.eventAt = eventAt;
        return history;
    }
}
