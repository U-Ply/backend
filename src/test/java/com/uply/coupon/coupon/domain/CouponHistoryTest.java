package com.uply.coupon.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CouponHistoryTest {

    private static final Long COUPON_ID = 1L;
    private static final LocalDateTime EVENT_AT = LocalDateTime.of(2026, 8, 13, 12, 0);

    @Test
    void createsIssuedHistory() {
        String idempotencyKey = "550e8400-e29b-41d4-a716-446655440000";

        CouponHistory history = CouponHistory.issued(COUPON_ID, idempotencyKey);

        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(history.getEventAt()).isNotNull();
    }

    @Test
    void createsUsedHistory() {
        String idempotencyKey = "550e8400-e29b-41d4-a716-446655440001";

        CouponHistory history = CouponHistory.used(COUPON_ID, idempotencyKey, EVENT_AT);

        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.USED);
        assertThat(history.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(history.getEventAt()).isEqualTo(EVENT_AT);
    }

    @Test
    void createsCancelledHistory() {
        String idempotencyKey = "550e8400-e29b-41d4-a716-446655440002";

        CouponHistory history = CouponHistory.cancelled(COUPON_ID, idempotencyKey, EVENT_AT);

        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(history.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(history.getEventAt()).isEqualTo(EVENT_AT);
    }

    @Test
    void createsExpiredHistory() {
        String idempotencyKey = "550e8400-e29b-41d4-a716-446655440003";

        CouponHistory history = CouponHistory.expired(COUPON_ID, idempotencyKey, EVENT_AT);

        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(history.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(history.getEventAt()).isEqualTo(EVENT_AT);
    }
}
