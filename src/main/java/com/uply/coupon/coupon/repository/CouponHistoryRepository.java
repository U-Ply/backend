package com.uply.coupon.coupon.repository;

import com.uply.coupon.coupon.domain.CouponHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    Optional<CouponHistory> findByIdempotencyKey(String idempotencyKey);
}
