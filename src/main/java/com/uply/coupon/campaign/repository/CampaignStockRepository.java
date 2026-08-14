package com.uply.coupon.campaign.repository;

import com.uply.coupon.campaign.domain.CampaignStock;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

// 비관적 락 조회 - DB로 락 걸고 커밋/롤백할 떄까지 대기 -> 무한대기 x timeout적용
public interface CampaignStockRepository extends JpaRepository<CampaignStock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM CampaignStock s WHERE s.id = :stockId")
    Optional<CampaignStock> findByIdForUpdate(@Param("stockId") Long stockId);
}
