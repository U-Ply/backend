package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.infrastructure.RedisStockIdLookup;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 캐시에 사전 적재된 데이터에서 stockId 조회 테스트 */
@SpringBootTest
class CampaignCacheWarmupIntegrationTest {

    @Autowired private CampaignCacheWarmupService campaignCacheWarmupService;

    @Autowired private RedisStockIdLookup redisStockIdLookup;

    @Autowired private CampaignRepository campaignRepository;

    @Autowired private CampaignStockRepository campaignStockRepository;

    @Autowired private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        // 테스트 간 독립성을 위해 Redis 플러시
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("캐시 웜업 실행 후, RedisStockIdLookup을 통해 stockId를 정상 조회한다.")
    void warmupAndLookupStockId_Success() {
        // given
        LocalDateTime now = LocalDateTime.now();

        // 1. Campaign 엔티티 생성 (name, openAt, expireAt 필수)
        Campaign campaign =
                Campaign.builder()
                        .name("신규 노선 오픈 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        String routeId = "ICN-NRT";
        String fareClass = "Y";

        // 2. CampaignStock 엔티티 생성 (campaign, routeId, fareClass, totalStock 필수)
        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId(routeId)
                        .fareClass(fareClass)
                        .totalStock(100)
                        .build();
        CampaignStock savedStock = campaignStockRepository.save(stock);

        // when
        // 3. DB 데이터를 기반으로 Redis 캐시 웜업 실행
        campaignCacheWarmupService.warmupCampaign(savedCampaign.getId());

        // 4. Redis에서 stockId lookup 수행
        Long foundStockId =
                redisStockIdLookup.lookupStockId(savedCampaign.getId(), routeId, fareClass);

        // then
        // 5. DB 저장 PK와 Redis lookup 결과 비교
        assertThat(foundStockId).isNotNull();
        assertThat(foundStockId).isEqualTo(savedStock.getId());
    }
}
