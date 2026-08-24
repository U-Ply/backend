package com.uply.coupon.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class CouponIssuedEventProcessorTest {

    private static final CouponIssuedEvent EVENT =
            new CouponIssuedEvent(
                    1001L,
                    10L,
                    1L,
                    3L,
                    "550e8400-e29b-41d4-a716-446655440000",
                    Instant.parse("2026-08-15T01:00:00Z"),
                    Instant.parse("2026-09-01T01:00:00Z"),
                    Instant.parse("2026-08-15T01:00:00.050Z"));

    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;
    @Mock private CouponIssuedPersistenceService persistenceService;
    @Mock private CouponIssuanceProgressRepository progressRepository;

    @InjectMocks private CouponIssuedEventProcessor eventProcessor;

    // 신규 이벤트가 DB 반영 서비스로 전달되는지 확인
    @Test
    void newEventIsPersisted() {
        assertThat(eventProcessor.process(EVENT)).isTrue();

        verify(persistenceService).persist(EVENT);
        verify(progressRepository).clear(EVENT.couponId());
    }

    @Test
    void pendingCleanupFailureDoesNotFailPersistedEvent() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(progressRepository)
                .clear(EVENT.couponId());

        assertThat(eventProcessor.process(EVENT)).isTrue();

        verify(persistenceService).persist(EVENT);
        verify(progressRepository).clear(EVENT.couponId());
    }

    // 이미 처리한 Kafka 이벤트가 다시 들어와도 쿠폰/이력/재고를 다시 변경하지 않는지 확인하는 테스트
    @Test
    void alreadyStoredCouponIsSkipped() {
        when(couponRepository.existsById(EVENT.couponId())).thenReturn(true);

        assertThat(eventProcessor.process(EVENT)).isFalse();

        verify(persistenceService, never()).persist(EVENT);
        verify(progressRepository).clear(EVENT.couponId());
    }

    @Test
    void pendingCleanupFailureDoesNotFailDuplicateEvent() {
        when(couponRepository.existsById(EVENT.couponId())).thenReturn(true);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(progressRepository)
                .clear(EVENT.couponId());

        assertThat(eventProcessor.process(EVENT)).isFalse();

        verify(persistenceService, never()).persist(EVENT);
        verify(progressRepository).clear(EVENT.couponId());
    }

    // 동시에 들어온 정보 중 먼저 성공한 처리만 인정하고 늦게 들어온 중복 처리는 추가 반영 없이 정상 종료한는 확인하는 테스트
    @Test
    void concurrentDuplicateIsSkippedAfterUniqueConstraintFailure() {
        when(couponRepository.existsById(EVENT.couponId())).thenReturn(false, true);
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(persistenceService)
                .persist(EVENT);

        assertThat(eventProcessor.process(EVENT)).isFalse();
        verify(progressRepository).clear(EVENT.couponId());
    }

    // 멱등성 키가 비어 있는 이벤트를 DB 저장 전에 차단하는지 확인
    @Test
    void nonDuplicateConstraintFailureIsRethrown() {
        doThrow(new DataIntegrityViolationException("unexpected"))
                .when(persistenceService)
                .persist(EVENT);

        assertThatThrownBy(() -> eventProcessor.process(EVENT))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(progressRepository, never()).clear(EVENT.couponId());
    }

    // 필수 값이 잘못된 이벤트를 저장 전 차단하는지 확인하는 테스트
    @Test
    void missingIdempotencyKeyIsRejected() {
        CouponIssuedEvent invalidEvent =
                new CouponIssuedEvent(
                        1001L,
                        10L,
                        1L,
                        3L,
                        " ",
                        Instant.parse("2026-08-15T01:00:00Z"),
                        Instant.parse("2026-09-01T01:00:00Z"),
                        Instant.parse("2026-08-15T01:00:00.050Z"));

        assertThatThrownBy(() -> eventProcessor.process(invalidEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        verify(persistenceService, never()).persist(invalidEvent);
        verify(progressRepository, never()).clear(invalidEvent.couponId());
    }
}
