package com.uply.coupon.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponIssuedPersistenceServiceTest {

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 8, 15, 1, 0);
    private static final LocalDateTime EXPIRE_AT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final CouponIssuedEvent EVENT =
            new CouponIssuedEvent(
                    1001L,
                    10L,
                    1L,
                    3L,
                    "550e8400-e29b-41d4-a716-446655440000",
                    Instant.parse("2026-08-15T01:00:00Z"),
                    Instant.parse("2026-08-15T01:00:00.050Z"));

    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;
    @Mock private CampaignStockRepository campaignStockRepository;

    @InjectMocks private CouponIssuedPersistenceService persistenceService;

    // 신규 발급 이벤트의 쿠폰/이력 저장과 재고 감소가 순서대로 실행되는지 확인
    @Test
    void persistsCouponHistoryAndDecreasesStock() {
        when(campaignStockRepository.findCouponExpireAt(EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(Optional.of(EXPIRE_AT));
        when(campaignStockRepository.decreaseRemainingStockIfAvailable(
                        EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(1);

        persistenceService.persist(EVENT);

        ArgumentCaptor<Coupon> couponCaptor = ArgumentCaptor.forClass(Coupon.class);
        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponRepository).saveAndFlush(couponCaptor.capture());
        verify(couponHistoryRepository).saveAndFlush(historyCaptor.capture());

        Coupon coupon = couponCaptor.getValue();
        assertThat(coupon.getCouponId()).isEqualTo(EVENT.couponId());
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(coupon.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(coupon.getExpireAt()).isEqualTo(EXPIRE_AT);

        CouponHistory history = historyCaptor.getValue();
        assertThat(history.getCouponId()).isEqualTo(EVENT.couponId());
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(history.getIdempotencyKey()).isEqualTo(EVENT.idempotencyKey());
        assertThat(history.getEventAt()).isEqualTo(ISSUED_AT);

        InOrder inOrder =
                inOrder(couponRepository, couponHistoryRepository, campaignStockRepository);
        inOrder.verify(couponRepository).saveAndFlush(coupon);
        inOrder.verify(couponHistoryRepository).saveAndFlush(history);
        inOrder.verify(campaignStockRepository)
                .decreaseRemainingStockIfAvailable(EVENT.stockId(), EVENT.campaignId());
    }

    // 캠페인/재고 조합을 찾지 못하면 쿠폰과 이력을 저장하지 않는지 확인
    @Test
    void missingCampaignStockIsRejectedBeforeSaving() {
        when(campaignStockRepository.findCouponExpireAt(EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> persistenceService.persist(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("캠페인 재고");

        verify(couponRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(couponHistoryRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    // MySQL 재고 감소가 실패하면 예외를 발생시키는지 확인
    @Test
    void stockDecreaseFailureAbortsProcessing() {
        when(campaignStockRepository.findCouponExpireAt(EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(Optional.of(EXPIRE_AT));
        when(campaignStockRepository.decreaseRemainingStockIfAvailable(
                        EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(0);

        assertThatThrownBy(() -> persistenceService.persist(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고 차감");
    }
}
