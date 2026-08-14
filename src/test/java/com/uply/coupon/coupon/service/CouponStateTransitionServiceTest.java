package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uply.coupon.common.exception.CouponNotFoundException;
import com.uply.coupon.common.exception.InvalidStateTransitionException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponStateTransitionServiceTest {

    private static final Long COUPON_ID = 1L;
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDateTime EVENT_AT = LocalDateTime.of(2026, 8, 14, 12, 0);
    private static final LocalDateTime EXPIRE_AT = EVENT_AT.plusDays(1);

    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;

    @InjectMocks private CouponStateTransitionService service;

    @Test
    void savesUsedHistoryOnlyWhenConditionalUpdateSucceeds() {
        when(couponRepository.useIfIssued(COUPON_ID, EVENT_AT)).thenReturn(1);

        service.use(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());

        CouponHistory history = historyCaptor.getValue();
        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.USED);
        assertThat(history.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(history.getEventAt()).isEqualTo(EVENT_AT);
        verify(couponRepository, never()).findById(COUPON_ID);
    }

    @Test
    void savesCancelledHistoryOnlyWhenConditionalUpdateSucceeds() {
        when(couponRepository.cancelIfIssued(COUPON_ID, EVENT_AT)).thenReturn(1);

        service.cancel(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(CouponStatus.CANCELLED);
    }

    @Test
    void savesExpiredHistoryOnlyWhenConditionalUpdateSucceeds() {
        when(couponRepository.expireIfIssued(COUPON_ID, EVENT_AT)).thenReturn(1);

        service.expire(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(CouponStatus.EXPIRED);
    }

    @Test
    void doesNotSaveHistoryWhenCouponDoesNotExist() {
        when(couponRepository.useIfIssued(COUPON_ID, EVENT_AT)).thenReturn(0);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.use(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT))
                .isInstanceOf(CouponNotFoundException.class)
                .satisfies(
                        exception -> {
                            CouponNotFoundException notFoundException =
                                    (CouponNotFoundException) exception;
                            assertThat(notFoundException.getErrorCode())
                                    .isEqualTo("COUPON_NOT_FOUND");
                            assertThat(notFoundException.getCouponId()).isEqualTo(COUPON_ID);
                        });

        verify(couponHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotSaveHistoryWhenStateTransitionIsInvalid() {
        Coupon coupon = Coupon.issue(1L, 1L, 1L, EXPIRE_AT);
        coupon.use(EVENT_AT);
        when(couponRepository.cancelIfIssued(COUPON_ID, EVENT_AT.plusMinutes(1))).thenReturn(0);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(
                        () -> service.cancel(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT.plusMinutes(1)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .satisfies(
                        exception -> {
                            InvalidStateTransitionException transitionException =
                                    (InvalidStateTransitionException) exception;
                            assertThat(transitionException.getCurrentStatus())
                                    .isEqualTo(CouponStatus.USED);
                            assertThat(transitionException.getTargetStatus())
                                    .isEqualTo(CouponStatus.CANCELLED);
                        });

        verify(couponHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
