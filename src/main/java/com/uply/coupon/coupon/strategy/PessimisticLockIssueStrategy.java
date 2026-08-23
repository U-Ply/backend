package com.uply.coupon.coupon.strategy;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.repository.CampaignWindow;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
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

            LocalDateTime preLockTime = campaignStockRepository.currentDatabaseTime();

            // 오픈/만료 판정 기준을 databaseTime 하나로 통일한다.
            // Lua 경로가 Redis TIME으로 판정하듯, DB 경로는 게이트와 기록이 모두 DB 시계에서 나와야 한다
            CampaignWindow window =
                    campaignStockRepository
                            .findCampaignWindow(stockId, campaignId)
                            .orElseThrow(() -> new CampaignNotFoundException(campaignId, stockId));

            // 조기 실패: 락을 기다릴 필요조차 없는 요청을 여기서 거른다.
            if (window.getOpenAt() != null && preLockTime.isBefore(window.getOpenAt())) {
                return IssueResult.fail(IssueFailReason.CAMPAIGN_NOT_OPEN);
            }
            if (!window.getExpireAt().isAfter(preLockTime)) {
                return IssueResult.fail(IssueFailReason.CAMPAIGN_EXPIRED);
            }

            CampaignStock stock =
                    campaignStockRepository
                            .findByIdForUpdate(stockId)
                            .orElseThrow(
                                    () -> new IllegalStateException("존재하지 않는 stockId: " + stockId));

            // 락 대기는 최대 3초(jakarta.persistence.lock.timeout)까지 걸릴 수 있다.
            // 그 사이 캠페인이 만료되면 위 조기 판정은 이미 낡은 시각을 본 것이 된다.
            // 락을 얻은 뒤 시각을 다시 재서 최종 판정하지 않으면, 만료 이후에 커밋되는 쿠폰이
            // 만료 이전 issued_at을 달고 나간다. openAt은 시간이 앞으로만 흐르므로 재확인이
            // 필요 없지만 expireAt은 반드시 다시 봐야 한다.
            LocalDateTime databaseTime = campaignStockRepository.currentDatabaseTime();
            LocalDateTime expireAt = window.getExpireAt();
            if (!expireAt.isAfter(databaseTime)) {
                return IssueResult.fail(IssueFailReason.CAMPAIGN_EXPIRED);
            }

            // 멱등성 확인 - 같은 키라도 캠페인/유저가 다르면 재사용으로 보고 거부한다
            Optional<CouponHistory> processed =
                    couponHistoryRepository.findByIdempotencyKey(idempotencyKey);
            if (processed.isPresent()) {
                Coupon existingCoupon =
                        couponRepository
                                .findById(processed.get().getCouponId())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "이력은 있는데 쿠폰이 없습니다: "
                                                                + processed.get().getCouponId()));
                if (!existingCoupon.getCampaignId().equals(campaignId)
                        || !existingCoupon.getUserId().equals(userId)) {
                    throw new IdempotencyKeyReusedException();
                }
                return IssueResult.success(
                        existingCoupon.getCouponId(),
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
