package com.uply.coupon.campaign.repository;

import com.uply.coupon.campaign.domain.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {}
