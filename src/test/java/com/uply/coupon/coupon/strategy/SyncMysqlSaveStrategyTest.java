package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.coupon.strategy.save.SyncMysqlSaveStrategy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class SyncMysqlSaveStrategyTest {

    @Mock private CouponRepository couponRepository;

    @Mock private CouponHistoryRepository couponHistoryRepository;

    @Mock private CampaignStockRepository campaignStockRepository;

    @InjectMocks private SyncMysqlSaveStrategy syncMysqlSaveStrategy;

    private long expireAtEpochMillis = 1780000000000L;
    private LocalDateTime expireAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);

    // 상위 전략이 전달하는 발급 시각 (Lua 경로는 Redis TIME, DB 경로는 NOW(3) 기준)
    private long issuedAtEpochMillis = 1770000000000L;
    private LocalDateTime issuedAt =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(issuedAtEpochMillis), ZoneOffset.UTC);

    @Test
    @DisplayName("SyncMysqlSaveStrategy 호출 시 Coupon과 CouponHistory가 정상 저장된다")
    void save_Success() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);

        // when
        syncMysqlSaveStrategy.save(
                couponId, userId, campaignId, stockId, idempotencyKey, issuedAt, expireAt);

        // then
        verify(campaignStockRepository).decreaseRemainingStockIfAvailable(stockId, campaignId);

        // 전달받은 issuedAt이 그대로 저장되어야 한다 (JVM now()로 새로 만들면 여기서 깨진다)
        ArgumentCaptor<Coupon> couponCaptor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository).save(couponCaptor.capture());
        assertThat(couponCaptor.getValue().getIssuedAt()).isEqualTo(issuedAt);
        assertThat(couponCaptor.getValue().getExpireAt()).isEqualTo(expireAt);

        // INV-04 대비 - 발급 이력의 event_at은 같은 행의 issued_at과 같아야 한다
        ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
        verify(couponHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getEventAt()).isEqualTo(issuedAt);
    }

    @Test
    @DisplayName("DB 재고 부족으로 차감 실패 시 OUT_OF_STOCK 예외가 발생한다")
    void save_OutOfStock_ThrowsException() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(0);

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("DataIntegrityViolationException 발생 시 ALREADY_ISSUED 예외로 변환되며 원인 예외가 유지된다")
    void save_DataIntegrityViolationException_ThrowsAlreadyIssued() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willReturn(1);
        given(couponRepository.save(any(Coupon.class)))
                .willThrow(
                        new DataIntegrityViolationException("Duplicate entry for key 'UK_coupon'"));

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.ALREADY_ISSUED);
    }

    @Test
    @DisplayName("기타 DB 인프라 예외 발생 시 DB_SAVE_FAILED 예외로 변환되며 원인 예외가 유지된다")
    void save_GeneralException_ThrowsDbSaveFailed() {
        // given
        Long couponId = 1000L;
        Long userId = 100L;
        Long campaignId = 1L;
        Long stockId = 10L;
        String idempotencyKey = "idempotency-key-123";

        given(campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId))
                .willThrow(new PessimisticLockingFailureException("Lock Timeout"));

        // when & then
        assertThatThrownBy(
                        () ->
                                syncMysqlSaveStrategy.save(
                                        couponId,
                                        userId,
                                        campaignId,
                                        stockId,
                                        idempotencyKey,
                                        issuedAt,
                                        expireAt))
                .isInstanceOf(CouponIssueException.class)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);
    }
}
