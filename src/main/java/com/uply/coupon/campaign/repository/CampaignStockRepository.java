package com.uply.coupon.campaign.repository;

import com.uply.coupon.campaign.domain.CampaignStock;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

// 비관적 락 조회 - DB로 락 걸고 커밋/롤백할 떄까지 대기 -> 무한대기 x timeout적용
public interface CampaignStockRepository extends JpaRepository<CampaignStock, Long> {

    interface RouteFareProjection {

        String getRouteId();

        String getFareClass();
    }

    @Query(
            """
            SELECT s
              FROM CampaignStock s
              JOIN FETCH s.campaign c
             WHERE c.id = :campaignId
               AND s.routeId = :routeId
               AND s.fareClass = :fareClass
            """)
    Optional<CampaignStock> findByCampaignIdAndRouteIdAndFareClass(
            @Param("campaignId") Long campaignId,
            @Param("routeId") String routeId,
            @Param("fareClass") String fareClass);

    @Query(value = "SELECT NOW(3)", nativeQuery = true)
    LocalDateTime currentDatabaseTime();

    @Query(
            """
            select c.expireAt
              from CampaignStock s
              join s.campaign c
             where s.id = :stockId
               and c.id = :campaignId
            """)
    Optional<LocalDateTime> findCouponExpireAt(
            @Param("stockId") Long stockId, @Param("campaignId") Long campaignId);

    @Query(
            """
            select c.openAt as openAt, c.expireAt as expireAt
              from CampaignStock s
              join s.campaign c
             where s.id = :stockId
               and c.id = :campaignId
            """)
    Optional<CampaignWindow> findCampaignWindow(
            @Param("stockId") Long stockId, @Param("campaignId") Long campaignId);

    @Query(
            """
            select s.routeId as routeId,
                   s.fareClass as fareClass
              from CampaignStock s
             where s.id = :stockId
               and s.campaign.id = :campaignId
            """)
    Optional<RouteFareProjection> findRouteFareByStockIdAndCampaignId(
            @Param("stockId") Long stockId, @Param("campaignId") Long campaignId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update CampaignStock s
               set s.remainingStock = s.remainingStock - 1
             where s.id = :stockId
               and s.campaign.id = :campaignId
               and s.remainingStock > 0
            """)
    int decreaseRemainingStockIfAvailable(
            @Param("stockId") Long stockId, @Param("campaignId") Long campaignId);

    // 실제 대기 한계는 innodb_lock_wait_timeout(50초)이다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM CampaignStock s WHERE s.id = :stockId")
    Optional<CampaignStock> findByIdForUpdate(@Param("stockId") Long stockId);

    List<CampaignStock> findAllByCampaignId(Long campaignId);
}
