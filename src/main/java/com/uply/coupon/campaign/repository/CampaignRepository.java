package com.uply.coupon.campaign.repository;

import com.uply.coupon.campaign.domain.Campaign;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query(value = "SELECT NOW(3)", nativeQuery = true)
    LocalDateTime currentDatabaseTime();
}
