package com.uply.coupon.coupon.strategy.save;

import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CouponSaveStrategy 전략 中 1 : MySql 동기 저장 */
@Component
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "sync-db")
@RequiredArgsConstructor
public class SyncMysqlSaveStrategy implements CouponSaveStrategy {

    /** coupons 테이블의 캠페인별 1인 1매 UNIQUE 제약 이름 */
    private static final String UNIQUE_CAMPAIGN_USER = "uk_campaign_user";

    private final CouponRepository couponRepository;
    private final CouponHistoryRepository couponHistoryRepository;
    private final CampaignStockRepository campaignStockRepository;

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
            // saveAndFlush로 제약 위반을 이 메서드 안에서 터뜨린다. 지연 flush를 두면 실패가
            // 트랜잭션 커밋 시점(= 프록시 경계)으로 밀려 아래 catch를 지나치고, 호출자인
            // LuaScriptIssueStrategy의 일반 catch로 들어가 Redis 보상 없이 재고가 샌다.
            Coupon coupon = Coupon.issue(couponId, userId, campaignId, stockId, issuedAt, expireAt);
            couponRepository.saveAndFlush(coupon);
            couponHistoryRepository.saveAndFlush(
                    CouponHistory.issued(coupon.getCouponId(), idempotencyKey, issuedAt));

        } catch (CouponIssueException e) {
            // 재고 부족은 그대로 재전파
            throw e;
        } catch (PessimisticLockingFailureException e) {
            // 락 대기 한계 초과. CannotAcquireLockException(MySQL lock wait timeout)도
            // 이 타입의 하위라 함께 걸린다. k6가 coupon_lock_timeout으로 따로 집계하고
            // 503 재시도 가능으로 응답해야 하므로 DB 저장 실패와 섞지 않는다.
            throw new CouponIssueException(IssueFailReason.LOCK_TIMEOUT, e);
        } catch (DataIntegrityViolationException e) {
            throw new CouponIssueException(classifyIntegrityViolation(e), e);
        } catch (Exception e) {
            // Connection 고갈 등 시스템/인프라 예외 (원인 e 포함)
            throw new CouponIssueException(IssueFailReason.DB_SAVE_FAILED, e);
        }
    }

    /**
     * 제약조건별로 실패 사유를 가른다.
     *
     * <p>정합성 위반을 뭉뚱그리면 두 방향으로 다 틀린다. FK·CHECK 위반까지 ALREADY_ISSUED로 응답하면 클라이언트가 "이미 발급받았다"는 잘못된 사실을
     * 통보받고, 반대로 전부 DB_SAVE_FAILED로 처리하면 정상적인 1인 1매 거부가 500으로 나간다.
     *
     * <p>제약 이름을 먼저 보고, 드라이버가 이름을 주지 않을 때만 메시지를 본다. 메시지 매칭은 DB·드라이버 버전에 따라 깨질 수 있어 최후 수단이다.
     */
    private IssueFailReason classifyIntegrityViolation(DataIntegrityViolationException exception) {
        String constraintName = constraintNameOf(exception);

        // uk_campaign_user : 동일 캠페인 1인 1매 위반 -> 409 ALREADY_ISSUED
        if (constraintName != null && constraintName.contains(UNIQUE_CAMPAIGN_USER)) {
            return IssueFailReason.ALREADY_ISSUED;
        }

        // uq_idempotency_key : 저장 단계의 멱등성 충돌.
        // 요청 단위 멱등성은 Redis 계층이 담당하므로, 여기까지 왔다는 것은 그 계층이 뚫렸다는 뜻이다.
        // 정상 거부가 아니라 설계 위반이므로 500으로 드러낸다.
        return IssueFailReason.DB_SAVE_FAILED;
    }

    /** Hibernate가 제약 이름을 알려주면 그 값을, 아니면 원인 메시지를 소문자로 돌려준다. */
    private String constraintNameOf(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof ConstraintViolationException constraintViolation
                && constraintViolation.getConstraintName() != null) {
            return constraintViolation.getConstraintName().toLowerCase(Locale.ROOT);
        }

        String message = exception.getMostSpecificCause().getMessage();
        return message == null ? null : message.toLowerCase(Locale.ROOT);
    }
}
