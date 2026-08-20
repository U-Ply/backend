package com.uply.coupon.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.infrastructure.RedisStockIdLookup;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.coupon.domain.Coupon;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Redis 캐시에 사전 적재된 데이터에서 stockId 조회 및 캐시 웜업 복구 테스트 */
@SpringBootTest
@Transactional
class CampaignCacheWarmupIntegrationTest {

    @Autowired private CampaignCacheWarmupService campaignCacheWarmupService;

    @Autowired private RedisStockIdLookup redisStockIdLookup;

    @Autowired private CampaignRepository campaignRepository;

    @Autowired private CampaignStockRepository campaignStockRepository;

    @Autowired private CouponRepository couponRepository;

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private JdbcTemplate jdbcTemplate;

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

    @Test
    @DisplayName("캐시 웜업 실행 시 DB의 잔여 재고(remainingStock)와 기발급 유저 Set이 정확히 복구된다.")
    void warmupCampaign_RestoresRemainingStockAndIssuedSet() {
        // given
        LocalDateTime now = LocalDateTime.now();

        Campaign campaign =
                Campaign.builder()
                        .name("제주 항공권 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        // 총재고 100개 중 30개가 소진되어 잔여 재고가 70개인 상황
        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("GMP-CJU")
                        .fareClass("Y")
                        .totalStock(100)
                        .build();
        stock.decreaseStock(30); // 재고 30개 소진
        CampaignStock savedStock = campaignStockRepository.save(stock);

        String sql = "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)";

        List<Object[]> batchArgs =
                List.of(
                        new Object[] {100L, "user1@test.com", "유저100"},
                        new Object[] {101L, "user2@test.com", "유저101"},
                        new Object[] {102L, "user3@test.com", "유저102"});

        jdbcTemplate.batchUpdate(sql, batchArgs);

        // DB에 기발급된 쿠폰 데이터 3건 생성 (유저 ID: 100, 101, 102)
        LocalDateTime issuedAt = savedCampaign.getOpenAt();
        Coupon coupon1 =
                Coupon.issue(
                        2001L,
                        100L,
                        savedCampaign.getId(),
                        savedStock.getId(),
                        issuedAt,
                        savedCampaign.getExpireAt());
        Coupon coupon2 =
                Coupon.issue(
                        2002L,
                        101L,
                        savedCampaign.getId(),
                        savedStock.getId(),
                        issuedAt,
                        savedCampaign.getExpireAt());
        Coupon coupon3 =
                Coupon.issue(
                        2003L,
                        102L,
                        savedCampaign.getId(),
                        savedStock.getId(),
                        issuedAt,
                        savedCampaign.getExpireAt());
        couponRepository.saveAll(List.of(coupon1, coupon2, coupon3));

        // when
        campaignCacheWarmupService.warmupCampaign(savedCampaign.getId());

        // then
        // 1. stock:{stockId} 검증 - totalStock(100)이 아닌 remainingStock(70)으로 적재되었는지 확인
        String stockKey = String.format("stock:%d", savedStock.getId());
        String cachedStock = redisTemplate.opsForValue().get(stockKey);
        assertThat(cachedStock).isNotNull();
        assertThat(Integer.parseInt(cachedStock)).isEqualTo(70);

        // 2. issued:{campaignId} Set 검증 - DB의 기발급 유저 ID(100, 101, 102)가 정상 복구되었는지 확인
        String issuedKey = String.format("issued:%d", savedCampaign.getId());
        Set<String> issuedUsers = redisTemplate.opsForSet().members(issuedKey);
        assertThat(issuedUsers).isNotNull();
        assertThat(issuedUsers).containsExactlyInAnyOrder("100", "101", "102");
    }
}
