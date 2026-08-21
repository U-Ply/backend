package com.uply.coupon.coupon.strategy.save;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CouponSaveStrategy 전략 中 1 : MySql 동기 저장 */
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "sync-db")
@RequiredArgsConstructor
public class SyncMysqlSaveStrategy implements CouponSaveStrategy {

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void save(
            Long couponId,
            Long userId,
            Long campaignId,
            Long stockId,
            String idempotencyKey,
            LocalDateTime issuedAt,
            LocalDateTime expireAt) {

        try {
            // 1. DB 원자적 재고 차감 실행 (영향받은 행 수가 0이면 재고 부족 또는 mismatch)
            int updatedCount =
                    campaignStockRepository.decreaseRemainingStockIfAvailable(stockId, campaignId);
            if (updatedCount == 0) {
                throw new CouponIssueException(IssueFailReason.OUT_OF_STOCK);
            }

            // 2. 쿠폰 발급, 히스토리 DB 저장
            Coupon coupon = Coupon.issue(couponId, userId, campaignId, stockId, issuedAt, expireAt);
            couponRepository.save(coupon);
            couponHistoryRepository.save(
                    CouponHistory.issued(coupon.getCouponId(), idempotencyKey, issuedAt));

            entityManager.flush();

        } catch (CouponIssueException e) {
            // 재고 부족은 그대로 재전파
            throw e;
        } catch (DuplicateKeyException e) {
            // UNIQUE(campaign_id, user_id) 또는 coupon_history.idempotency_key 위반.
            // 중복 발급 시도로 확정할 수 있는 유일한 경우다.
            throw new CouponIssueException(IssueFailReason.ALREADY_ISSUED, e);
        } catch (DataIntegrityViolationException e) {
            throw new CouponIssueException(IssueFailReason.DB_SAVE_FAILED, e);
        } catch (Exception e) {
            // DB Lock Timeout, Connection 고갈 등 시스템/인프라 예외 (원인 e 포함)
            throw new CouponIssueException(IssueFailReason.DB_SAVE_FAILED, e);
        }
    }
}
