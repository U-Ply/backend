package com.uply.coupon.coupon.strategy;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** DB 비관적 락(SELECT ... FOR UPDATE) 기반 발급 전략 */
@Component("pessimisticLockIssueStrategy")
@RequiredArgsConstructor
public class PessimisticLockIssueStrategy implements CouponIssueStrategy {

    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CouponIdGenerator couponIdGenerator;

    @Override
    @Transactional // 락~저장까지 묶기 (원자성 보장)
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {

        try {

            LocalDateTime databaseTime = campaignStockRepository.currentDatabaseTime();

            LocalDateTime expireAt =
                    campaignStockRepository
                            .findCouponExpireAt(stockId, campaignId)
                            .orElseThrow(() -> new CampaignNotFoundException(campaignId, stockId));

            if (!expireAt.isAfter(databaseTime)) {
                return IssueResult.fail(IssueFailReason.CAMPAIGN_EXPIRED);
            }

            CampaignStock stock =
                    campaignStockRepository
                            .findByIdForUpdate(stockId)
                            .orElseThrow(
                                    () -> new IllegalStateException("존재하지 않는 stockId: " + stockId));

            // 멱등성 확인
            Optional<CouponHistory> processed =
                    couponHistoryRepository.findByIdempotencyKey(idempotencyKey);
            if (processed.isPresent()) {
                return IssueResult.success(processed.get().getCouponId());
            }

            // 중복 발급 확인
            if (couponRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
                return IssueResult.fail(IssueFailReason.ALREADY_ISSUED);
            }

            // 재고 확인
            if (stock.getRemainingStock() <= 0) {
                return IssueResult.fail(IssueFailReason.OUT_OF_STOCK);
            }

            // 재고 차감
            stock.decrease();
            campaignStockRepository.save(stock);

            // 쿠폰 발급 (expireAt은 임시로 7일 후 만료)
            Coupon coupon =
                    Coupon.issue(
                            couponIdGenerator.generate(),
                            userId,
                            campaignId,
                            stockId,
                            databaseTime,
                            expireAt);
            couponRepository.save(coupon);

            // 발급 이력 저장
            couponHistoryRepository.save(
                    CouponHistory.issued(coupon.getCouponId(), idempotencyKey));

            return IssueResult.success(coupon.getCouponId());

        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            return IssueResult.fail(IssueFailReason.LOCK_TIMEOUT);
        }
    }

    @Override
    public String name() {
        return "PESSIMISTIC_LOCK";
    }
}
