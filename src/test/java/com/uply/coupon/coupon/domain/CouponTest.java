package com.uply.coupon.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.common.exception.InvalidStateTransitionException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CouponTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 8, 13, 9, 0);
    private static final LocalDateTime EXPIRE_AT = LocalDateTime.of(2026, 8, 31, 23, 59);

    private Coupon issuedCoupon() {
        return Coupon.issue(1L, 1L, 1L, 1L, ISSUED_AT, EXPIRE_AT); // 오토박싱
    }

    @Test
    void issuedCouponCanBeUsed() {
        Coupon coupon = issuedCoupon();
        LocalDateTime usedAt = LocalDateTime.of(2026, 8, 13, 12, 0); // 테스트용 사용 시각

        coupon.use(usedAt);

        assertThat(coupon.getStatus())
                .isEqualTo(CouponStatus.USED); // 쿠폰 상태가 ISSUED에서 USED로 변경됐는지 검사
        assertThat(coupon.getUsedAt()).isEqualTo(usedAt); // 쿠폰 사용 시각에 전달한 usedAt이 정확히 저장됐는지 검사
        assertThat(coupon.getCancelledAt()).isNull(); // 사용 처리했을때 취소 시각이 잘 저장됬는지 확인용
        assertThat(coupon.getExpiredAt()).isNull(); // 사용 처리했을때 만료 시각이 잘 저장됬는지 확인용
    }

    @Test
    void usedCouponCanBeCancelled() {
        Coupon coupon = issuedCoupon();
        LocalDateTime usedAt = LocalDateTime.of(2026, 8, 13, 12, 0);
        LocalDateTime cancelledAt = usedAt.plusMinutes(1);
        coupon.use(usedAt);

        coupon.cancel(cancelledAt);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(coupon.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(coupon.getUsedAt()).isEqualTo(usedAt);
        assertThat(coupon.getExpiredAt()).isNull();
    }

    @Test
    void issuedCouponCanBeExpired() {
        Coupon coupon = issuedCoupon();
        LocalDateTime expiredAt = LocalDateTime.of(2026, 9, 1, 0, 0);

        coupon.expire(expiredAt);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(coupon.getExpiredAt()).isEqualTo(expiredAt);
        assertThat(coupon.getUsedAt()).isNull();
        assertThat(coupon.getCancelledAt()).isNull();
    }

    @Test
    void issuedCouponCanBeCancelledByAirline() {
        Coupon coupon = issuedCoupon();
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 8, 13, 12, 1);

        coupon.cancel(cancelledAt);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(coupon.getUsedAt()).isNull();
        assertThat(coupon.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(coupon.getExpiredAt()).isNull();
    }

    @Test
    void cancelledCouponCannotBeUsed() {
        Coupon coupon = issuedCoupon();
        LocalDateTime usedAt = LocalDateTime.of(2026, 8, 13, 12, 0);
        LocalDateTime cancelledAt = usedAt.plusMinutes(1);
        coupon.use(usedAt);
        coupon.cancel(cancelledAt);

        assertThatThrownBy(
                        () ->
                                coupon.use(
                                        cancelledAt.plusMinutes(
                                                1))) // 예외는 1분을 더했기 때문에 발생하는 것이 아니라 현재 상태가
                // CANCELLED여서 발생
                .isInstanceOf(InvalidStateTransitionException.class) // 발생한 예외가 상태 전이 규칙 위반을 나타내는
                // InvalidStateTransitionException인지 확인
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("USED"); // 이전 상태와 바꾸고 난 현재 상태가 가능한 상태 전이인지 확인하려고

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(coupon.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(coupon.getUsedAt()).isEqualTo(usedAt);
    }

    @Test
    void expiredCouponCannotBeUsed() {
        Coupon coupon = issuedCoupon();
        LocalDateTime expiredAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        coupon.expire(expiredAt);

        assertThatThrownBy(() -> coupon.use(expiredAt.plusMinutes(1)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("EXPIRED")
                .hasMessageContaining("USED");

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(coupon.getExpiredAt()).isEqualTo(expiredAt);
        assertThat(coupon.getUsedAt()).isNull();
    }
}
