package com.uply.coupon.coupon.strategy;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.repository.CampaignWindow;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

            // 오픈/만료 판정 기준을 databaseTime 하나로 통일한다.
            // Lua 경로가 Redis TIME으로 판정하듯, DB 경로는 게이트와 기록이 모두 DB 시계에서 나와야 한다
            CampaignWindow window =
                    campaignStockRepository
                            .findCampaignWindow(stockId, campaignId)
                            .orElseThrow(() -> new CampaignNotFoundException(campaignId, stockId));

            if (window.getOpenAt() != null && databaseTime.isBefore(window.getOpenAt())) {
                return IssueResult.fail(IssueFailReason.CAMPAIGN_NOT_OPEN);
            }

            LocalDateTime expireAt = window.getExpireAt();
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
                return IssueResult.success(
                        processed.get().getCouponId(),
                        processed.get().getEventAt().toInstant(ZoneOffset.UTC),
                        expireAt.toInstant(ZoneOffset.UTC));
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

            // 쿠폰 발급 - 만료 시각은 캠페인에서 상속
            Coupon coupon =
                    Coupon.issue(
                            couponIdGenerator.generate(),
                            userId,
                            campaignId,
                            stockId,
                            databaseTime,
                            expireAt);
            couponRepository.save(coupon);

            // 발급 이력 저장 - 쿠폰과 같은 databaseTime을 넘겨 event_at과 issued_at을 일치하도록
            couponHistoryRepository.save(
                    CouponHistory.issued(coupon.getCouponId(), idempotencyKey, databaseTime));

            return IssueResult.success(
                    coupon.getCouponId(),
                    databaseTime.toInstant(ZoneOffset.UTC),
                    expireAt.toInstant(ZoneOffset.UTC));

        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            return IssueResult.fail(IssueFailReason.LOCK_TIMEOUT);
        }
    }

    @Override
    public String name() {
        return "PESSIMISTIC_LOCK";
    }
}
