package com.uply.coupon.coupon.repository;

import com.uply.coupon.coupon.domain.CouponHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 쿠폰 상태 변경 이력을 저장하고 조회하는 Repository.
 *
 * <p>쿠폰 상태 변경과 이력 저장은 서비스 계층의 동일한 트랜잭션에서 처리합니다.
 */
public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<CouponHistory> findByIdempotencyKey(String idempotencyKey);

    @Query(
            value =
                    """
                    SELECT COUNT(*)
                      FROM coupon_history history
                      JOIN coupons coupon ON coupon.coupon_id = history.coupon_id
                     WHERE coupon.campaign_id = :campaignId
                       AND history.from_status = 'ISSUED'
                       AND history.to_status = 'CANCELLED'
                       AND history.idempotency_key LIKE CONCAT(:historyKeyPrefix, '%')
                    """,
            nativeQuery = true)
    long countCampaignRevocationsByHistoryKeyPrefix(
            @Param("campaignId") Long campaignId,
            @Param("historyKeyPrefix") String historyKeyPrefix);
}
