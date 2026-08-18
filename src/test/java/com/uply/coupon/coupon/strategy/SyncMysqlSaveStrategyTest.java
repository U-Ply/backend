package com.uply.coupon.coupon.strategy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

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
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ExtendWith(MockitoExtension.class)
class SyncMysqlSaveStrategyTest {

    @Mock private CouponRepository couponRepository;

    @Mock private CouponHistoryRepository couponHistoryRepository;

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

        // when
        syncMysqlSaveStrategy.save(couponId, userId, campaignId, stockId, idempotencyKey);

        // then
        verify(couponRepository).save(any(Coupon.class));
        verify(couponHistoryRepository).save(any(CouponHistory.class));
    }
}
