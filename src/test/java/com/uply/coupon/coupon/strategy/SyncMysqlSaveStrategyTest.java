package com.uply.coupon.coupon.strategy;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ExtendWith(MockitoExtension.class)
class SyncMysqlSaveStrategyTest {

    @Mock private CouponRepository couponRepository;

    @Mock private CouponHistoryRepository couponHistoryRepository;

    @Mock private CampaignStockRepository campaignStockRepository;

    @InjectMocks private SyncMysqlSaveStrategy syncMysqlSaveStrategy;

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
        syncMysqlSaveStrategy.save(couponId, userId, campaignId, stockId, idempotencyKey);

        // then
        verify(campaignStockRepository).decreaseRemainingStockIfAvailable(stockId, campaignId);
        verify(couponRepository).save(any(Coupon.class));
        verify(couponHistoryRepository).save(any(CouponHistory.class));
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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.OUT_OF_STOCK);
    }

    @Test
    @DisplayName("DB 접근 중 DataAccessException 발생 시 DB_SAVE_FAILED 예외로 변환된다")
    void save_DataAccessException_ThrowsException() {
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
                                        couponId, userId, campaignId, stockId, idempotencyKey))
                .isInstanceOf(CouponIssueException.class)
                .extracting("reason")
                .isEqualTo(IssueFailReason.DB_SAVE_FAILED);
    }
}
