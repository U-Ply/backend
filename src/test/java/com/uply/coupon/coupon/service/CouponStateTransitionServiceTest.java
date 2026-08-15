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

    // 사용 조건부 UPDATE가 1건 성공했을 때 ISSUED → USED 이력이 올바르게 저장되는지 확인
    @Test
    void savesUsedHistoryOnlyWhenConditionalUpdateSucceeds() {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, EXPIRE_AT);
        coupon.use(EVENT_AT);
        when(couponRepository.useIfIssued(COUPON_ID)).thenReturn(1);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        service.use(COUPON_ID, IDEMPOTENCY_KEY);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());

        CouponHistory history = historyCaptor.getValue();
        assertThat(history.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(history.getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.USED);
        assertThat(history.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(history.getEventAt()).isEqualTo(EVENT_AT);
        verify(couponRepository).findById(COUPON_ID);
    }

    // 취소 조건부 UPDATE가 1건 성공했을 때 ISSUED → CANCELLED 이력이 올바르게 저장되는지 확인
    @Test
    void savesCancelledHistoryOnlyWhenConditionalUpdateSucceeds() {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, EXPIRE_AT);
        coupon.cancel(EVENT_AT);
        when(couponRepository.cancelIfIssued(COUPON_ID)).thenReturn(1);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        service.cancel(COUPON_ID, IDEMPOTENCY_KEY);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(CouponStatus.CANCELLED);
        assertThat(historyCaptor.getValue().getEventAt()).isEqualTo(EVENT_AT);
    }

    // 만료 조건부 UPDATE가 1건 성공했을 때 ISSUED → EXPIRED 이력이 저장되고 true를 반환하는지 확인
    @Test
    void savesExpiredHistoryOnlyWhenConditionalUpdateSucceeds() {
        when(couponRepository.expireIfIssued(COUPON_ID, EVENT_AT)).thenReturn(1);

        boolean expired = service.expireCoupon(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT);

        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());
        assertThat(expired).isTrue();
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(historyCaptor.getValue().getEventAt()).isEqualTo(EVENT_AT);
    }

    // 만료 조건부 UPDATE가 0건이면 예외 없이 false를 반환하고 이력을 저장하지 않는지 확인
    @Test
    void returnsFalseAndDoesNotSaveHistoryWhenExpirationUpdateDoesNotSucceed() {
        when(couponRepository.expireIfIssued(COUPON_ID, EVENT_AT)).thenReturn(0);

        boolean expired = service.expireCoupon(COUPON_ID, IDEMPOTENCY_KEY, EVENT_AT);

        assertThat(expired).isFalse();
        verify(couponHistoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // 사용 조건부 UPDATE가 0건이고 쿠폰도 존재하지 않으면 COUPON_NOT_FOUND 예외가 발생하고 이력을 저장하지 않는지 확인
    @Test
    void doesNotSaveHistoryWhenCouponDoesNotExist() {
        when(couponRepository.useIfIssued(COUPON_ID)).thenReturn(0);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.use(COUPON_ID, IDEMPOTENCY_KEY))
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

    // 이미 USED인 쿠폰을 CANCELLED로 변경하려고 하면 INVALID_STATE_TRANSITION 예외가 발생하고 이력을 저장하지 않는지 확인
    @Test
    void doesNotSaveHistoryWhenStateTransitionIsInvalid() {
        Coupon coupon = Coupon.issue(COUPON_ID, 1L, 1L, 1L, EXPIRE_AT);
        coupon.use(EVENT_AT);
        when(couponRepository.cancelIfIssued(COUPON_ID)).thenReturn(0);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.cancel(COUPON_ID, IDEMPOTENCY_KEY))
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
