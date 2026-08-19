package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignCouponRevokeServiceTest {

    private static final Long CAMPAIGN_ID = 10L;
    private static final Long FIRST_COUPON_ID = 101L;
    private static final Long SKIPPED_COUPON_ID = 102L;
    private static final Long SECOND_COUPON_ID = 103L;
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDateTime EVENT_AT = LocalDateTime.of(2026, 8, 19, 12, 0);

    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;

    @InjectMocks private CampaignCouponRevokeService service;

    // 조건부 취소가 성공한 쿠폰만 이력이 저장되고 성공 건수에 포함되는지 확인
    @Test
    void savesHistoryOnlyForCouponsWhoseConditionalUpdateSucceeds() {
        Coupon firstCoupon = cancelledCoupon(FIRST_COUPON_ID, EVENT_AT);
        Coupon secondCoupon = cancelledCoupon(SECOND_COUPON_ID, EVENT_AT.plusSeconds(1));
        when(couponRepository.findIssuedCouponIdsByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(FIRST_COUPON_ID, SKIPPED_COUPON_ID, SECOND_COUPON_ID));
        when(couponRepository.revokeIfIssued(FIRST_COUPON_ID)).thenReturn(1);
        when(couponRepository.revokeIfIssued(SKIPPED_COUPON_ID)).thenReturn(0);
        when(couponRepository.revokeIfIssued(SECOND_COUPON_ID)).thenReturn(1);
        when(couponRepository.findById(FIRST_COUPON_ID)).thenReturn(Optional.of(firstCoupon));
        when(couponRepository.findById(SECOND_COUPON_ID)).thenReturn(Optional.of(secondCoupon));

        int revokedCount = service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository, org.mockito.Mockito.times(2)).save(historyCaptor.capture());

        assertThat(revokedCount).isEqualTo(2);
        assertThat(historyCaptor.getAllValues())
                .extracting(CouponHistory::getCouponId)
                .containsExactly(FIRST_COUPON_ID, SECOND_COUPON_ID);
        assertThat(historyCaptor.getAllValues())
                .extracting(CouponHistory::getFromStatus)
                .containsOnly(CouponStatus.ISSUED);
        assertThat(historyCaptor.getAllValues())
                .extracting(CouponHistory::getToStatus)
                .containsOnly(CouponStatus.CANCELLED);
        assertThat(historyCaptor.getAllValues())
                .extracting(CouponHistory::getIdempotencyKey)
                .containsExactly("revoke-101-" + IDEMPOTENCY_KEY, "revoke-103-" + IDEMPOTENCY_KEY);
        assertThat(historyCaptor.getAllValues())
                .extracting(CouponHistory::getEventAt)
                .containsExactly(EVENT_AT, EVENT_AT.plusSeconds(1));
        verify(couponRepository, never()).findById(SKIPPED_COUPON_ID);
    }

    // 조건부 취소가 모두 실패하면 이력을 저장하지 않고 성공 건수 0을 반환하는지 확인
    @Test
    void doesNotSaveHistoryWhenNoConditionalUpdateSucceeds() {
        when(couponRepository.findIssuedCouponIdsByCampaignId(CAMPAIGN_ID))
                .thenReturn(List.of(FIRST_COUPON_ID));
        when(couponRepository.revokeIfIssued(FIRST_COUPON_ID)).thenReturn(0);

        int revokedCount = service.revoke(CAMPAIGN_ID, IDEMPOTENCY_KEY);

        assertThat(revokedCount).isZero();
        verify(couponHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(couponRepository, never()).findById(FIRST_COUPON_ID);
    }

    private Coupon cancelledCoupon(Long couponId, LocalDateTime cancelledAt) {
        Coupon coupon =
                Coupon.issue(
                        couponId,
                        couponId + 1_000L,
                        CAMPAIGN_ID,
                        1L,
                        cancelledAt.minusDays(1),
                        cancelledAt.plusDays(1));
        coupon.cancel(cancelledAt);
        return coupon;
    }
}
