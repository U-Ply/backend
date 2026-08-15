package com.uply.coupon.campaign.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.uply.coupon.campaign.domain.Campaign;

public interface CampaignRepository extends JpaRepository<Campaign, Long>{

}
