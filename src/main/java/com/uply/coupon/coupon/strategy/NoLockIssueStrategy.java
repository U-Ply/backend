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
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동시성 제어를 전혀 하지 않은 baseline 전략 "왜 동시성 제어가 필요한가"를 증명하기 위한 대조군으로, 부하테스트 시 재고 초과 발급이 실제로 발생하는 것을 보여주는
 * 용도
 */
@Component("noLockIssueStrategy")
@RequiredArgsConstructor
public class NoLockIssueStrategy implements CouponIssueStrategy {

    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CouponIdGenerator couponIdGenerator;

    @Override
    @Transactional
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: 락/원자적 연산 없이 "재고 확인 → 차감"을 그대로 구현
        // 예: SELECT remaining_stock ... 확인 후 별도 UPDATE (조건 없이)
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
                        .findById(stockId)
                        .orElseThrow(
                                () -> new IllegalStateException("존재하지 않는 stockId: " + stockId));
        // 멱등성 확인
        Optional<CouponHistory> processed =
                couponHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (processed.isPresent()) {
            // 이미 발급된 건의 재조회이므로 databaseTime(지금 시각)이 아니라 저장된 이력의 시각을 돌려준다.
            return IssueResult.success(
                    processed.get().getCouponId(),
                    processed.get().getEventAt().toInstant(ZoneOffset.UTC),
                    expireAt.toInstant(ZoneOffset.UTC));
        }

        // 중복 발급 확인
        if (couponRepository.existsByCampaignIdAndUserId(campaignId, userId)) {
            return IssueResult.fail(IssueFailReason.ALREADY_ISSUED);
        }

        // 확인과 차감 사이에 다른 요청이 끼어들 수 있는 전형적인 read-modify-write 취약 구조
        if (stock.getRemainingStock() <= 0) {
            return IssueResult.fail(IssueFailReason.OUT_OF_STOCK);
        }

        stock.decrease();
        campaignStockRepository.save(stock);

        Coupon coupon =
                Coupon.issue(
                        couponIdGenerator.generate(),
                        userId,
                        campaignId,
                        stockId,
                        databaseTime,
                        expireAt);
        couponRepository.save(coupon);
        // 쿠폰과 같은 databaseTime을 넘겨 event_at과 issued_at을 일치시킨다 (INV-04)
        couponHistoryRepository.save(
                CouponHistory.issued(coupon.getCouponId(), idempotencyKey, databaseTime));

        return IssueResult.success(
                coupon.getCouponId(),
                databaseTime.toInstant(ZoneOffset.UTC),
                expireAt.toInstant(ZoneOffset.UTC));
    }

    @Override
    public String name() {
        return "NO_LOCK";
    }
}
