package com.uply.coupon.coupon.repository;

import com.uply.coupon.coupon.domain.CouponHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 쿠폰 상태 변경 이력을 저장하고 조회하는 Repository.
 *
 * <p>쿠폰 상태 변경과 이력 저장은 서비스 계층의 동일한 트랜잭션에서 처리합니다.
 */
public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<CouponHistory> findByIdempotencyKey(String idempotencyKey);
}
