package com.uply.coupon.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
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
                    Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-08-15T01:00:00.050Z"));

    @Mock private CouponRepository couponRepository;
    @Mock private CouponHistoryRepository couponHistoryRepository;
    @Mock private CampaignStockRepository campaignStockRepository;

    @InjectMocks private CouponIssuedPersistenceService persistenceService;

    // 신규 발급 이벤트의 쿠폰/이력 저장과 재고 감소가 순서대로 실행되는지 확인
    @Test
    void persistsCouponHistoryAndDecreasesStock() {
        // [수정] existsByIdAndCampaignId 검증 통과를 위해 true 반환 설정 추가
        when(campaignStockRepository.existsByIdAndCampaignId(EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(true);
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

    // MySQL 재고 감소가 실패하면 예외를 발생시키는지 확인
    @Test
    void stockDecreaseFailureAbortsProcessing() {
        // [수정] existsByIdAndCampaignId 검증 통과를 위해 true 반환 설정 추가
        when(campaignStockRepository.existsByIdAndCampaignId(EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(true);
        when(campaignStockRepository.decreaseRemainingStockIfAvailable(
                        EVENT.stockId(), EVENT.campaignId()))
                .thenReturn(0);

        assertThatThrownBy(() -> persistenceService.persist(EVENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고 차감");
    }

    // expireAt=null인 구버전 Kafka 이벤트가 DB에서 만료 시각을 조회해 정상 저장되는지 검증
    @Test
    void persist_LegacyEvent_SuccessWithDbFallback() {
        // given
        CouponIssuedEvent legacyEvent =
                new CouponIssuedEvent(
                        1001L,
                        10L,
                        1L,
                        3L,
                        "550e8400-e29b-41d4-a716-446655440000",
                        Instant.parse("2026-08-15T01:00:00Z"),
                        null, // 구버전 이벤트: expireAt 없음
                        Instant.parse("2026-08-15T01:00:00.050Z"));

        when(campaignStockRepository.findCouponExpireAt(
                        legacyEvent.stockId(), legacyEvent.campaignId()))
                .thenReturn(Optional.of(EXPIRE_AT));
        when(campaignStockRepository.existsByIdAndCampaignId(
                        legacyEvent.stockId(), legacyEvent.campaignId()))
                .thenReturn(true);
        when(campaignStockRepository.decreaseRemainingStockIfAvailable(
                        legacyEvent.stockId(), legacyEvent.campaignId()))
                .thenReturn(1);

        // when
        persistenceService.persist(legacyEvent);

        // then
        ArgumentCaptor<Coupon> couponCaptor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).saveAndFlush(couponCaptor.capture());

        Coupon coupon = couponCaptor.getValue();
        assertThat(coupon.getCouponId()).isEqualTo(legacyEvent.couponId());
        assertThat(coupon.getExpireAt()).isEqualTo(EXPIRE_AT); // DB에서 조회한 EXPIRE_AT이 정상 세팅되었는지 확인

        verify(campaignStockRepository)
                .findCouponExpireAt(legacyEvent.stockId(), legacyEvent.campaignId());
    }

    // 2. expireAt=null이고 DB에도 재고 풀이 없으면 실패(IllegalStateException)하는지 검증
    @Test
    void persist_LegacyEvent_NotFoundInDb_ThrowsException() {
        // given
        CouponIssuedEvent legacyEvent =
                new CouponIssuedEvent(
                        1001L,
                        10L,
                        1L,
                        3L,
                        "550e8400-e29b-41d4-a716-446655440000",
                        Instant.parse("2026-08-15T01:00:00Z"),
                        null, // 구버전 이벤트: expireAt 없음
                        Instant.parse("2026-08-15T01:00:00.050Z"));

        // DB Fallback 조회 시 empty 반환
        when(campaignStockRepository.findCouponExpireAt(
                        legacyEvent.stockId(), legacyEvent.campaignId()))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> persistenceService.persist(legacyEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이벤트와 일치하는 캠페인 재고를 찾을 수 없습니다");

        verify(campaignStockRepository)
                .findCouponExpireAt(legacyEvent.stockId(), legacyEvent.campaignId());
    }
}
