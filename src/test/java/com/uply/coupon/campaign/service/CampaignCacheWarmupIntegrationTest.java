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

        String sql =
                "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE user_id = user_id";

        List<Object[]> batchArgs =
                List.of(
                        new Object[] {100L, "warmup-user100@test.com", "유저100"},
                        new Object[] {101L, "warmup-user101@test.com", "유저101"},
                        new Object[] {102L, "warmup-user102@test.com", "유저102"});

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

    @Test
    @DisplayName("기존 Redis에 고스트 유저가 남아있을 때 웜업 실행 시 RENAME을 통해 고스트 유저가 제거된다.")
    void warmupCampaign_RemovesGhostUser_ViaAtomicRename() {
        // given
        LocalDateTime now = LocalDateTime.now();

        Campaign campaign =
                Campaign.builder()
                        .name("동남아 노선 특가 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("ICN-BKK")
                        .fareClass("Y")
                        .totalStock(100)
                        .build();
        CampaignStock savedStock = campaignStockRepository.save(stock);

        // Redis에 DB 저장이 실패하여 남아있는 고스트 유저(999L) 및 기존 유저 데이터 강제 주입
        String issuedKey = String.format("issued:%d", savedCampaign.getId());
        redisTemplate.opsForSet().add(issuedKey, "999"); // DB에 없는 고스트 유저

        // DB에는 유저 100L만 정상 발급 상태로 저장
        String sql = "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, 100L, "user100@test.com", "유저100");

        // 발급 시각은 호출부가 명시한다. JVM now()로 채우던 오버로드는 시계가 섞여 제거됐다.
        Coupon coupon =
                Coupon.issue(
                        3001L,
                        100L,
                        savedCampaign.getId(),
                        savedStock.getId(),
                        savedCampaign.getOpenAt(),
                        savedCampaign.getExpireAt());
        couponRepository.save(coupon);

        // when
        campaignCacheWarmupService.warmupCampaign(savedCampaign.getId());

        // then
        // 1. 고스트 유저(999)는 제거되고 DB 기준 유저(100)만 정확히 잔존해야 함
        Set<String> restoredUsers = redisTemplate.opsForSet().members(issuedKey);
        assertThat(restoredUsers).isNotNull();
        assertThat(restoredUsers).containsExactly("100");
        assertThat(restoredUsers).doesNotContain("999");

        // 2. 스왑에 사용된 임시 키(temp:issued:{campaignId})가 깔끔히 정리되었는지 검증
        String tempIssuedKey = String.format("temp:issued:%d", savedCampaign.getId());
        Boolean hasTempKey = redisTemplate.hasKey(tempIssuedKey);
        assertThat(hasTempKey).isFalse();
    }

    @Test
    @DisplayName("DB에 발급 내역이 완전히 없는 경우 웜업 실행 시 오염된 기존 issued Set이 삭제된다.")
    void warmupCampaign_ClearsIssuedSet_WhenNoCouponsInDb() {
        // given
        LocalDateTime now = LocalDateTime.now();

        Campaign campaign =
                Campaign.builder()
                        .name("미주 노선 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("ICN-LAX")
                        .fareClass("Y")
                        .totalStock(50)
                        .build();
        campaignStockRepository.save(stock);

        // Redis에 고스트 유저 데이터(888L)가 잔존하는 상황
        String issuedKey = String.format("issued:%d", savedCampaign.getId());
        redisTemplate.opsForSet().add(issuedKey, "888");

        // DB에는 쿠폰 발급 내역이 0건인 상태 유지

        // when
        campaignCacheWarmupService.warmupCampaign(savedCampaign.getId());

        // then
        // DB에 발급 유저가 없으므로 기존 오염 키가 tamamen 삭제(DELETE)되었는지 검증
        Boolean hasIssuedKey = redisTemplate.hasKey(issuedKey);
        assertThat(hasIssuedKey).isFalse();
    }

    // recoverMissingCache: 운영 중 부분 유실 복구. warmupCampaign 테스트와 달리, 실제
    // Redis GET/SETNX로 "살아있는 키는 절대 덮어쓰지 않는다"를 직접 검증한다.

    @Test
    @DisplayName("부분 복구: 실시간으로 감소 중인 stock 값은 그대로 두고, 없는 expireAt만 채운다.")
    void recoverMissingCache_PreservesLiveStockValue_FillsOnlyMissingKey() {
        // given
        LocalDateTime now = LocalDateTime.now();
        Campaign campaign =
                Campaign.builder()
                        .name("나고야 노선 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("ICN-NGO")
                        .fareClass("Y")
                        .totalStock(100)
                        .build();
        stock.decreaseStock(30); // DB 기준 잔여 70
        CampaignStock savedStock = campaignStockRepository.save(stock);

        // 이미 웜업된 상태에서, 발급이 계속 진행돼 Redis 재고가 DB보다 한 걸음 더 앞선(65) 상황을
        // 흉내낸다. expireAt 키만 유실됐다고 가정한다 (openAt은 이 테스트의 관심사가 아니다).
        String stockKey = String.format("stock:%d", savedStock.getId());
        redisTemplate.opsForValue().set(stockKey, "65");

        // when
        List<String> mismatches =
                campaignCacheWarmupService.recoverMissingCache(savedCampaign.getId());

        // then
        // stock 값이 SETNX로 덮어써졌다면 70(DB 스냅샷)이 됐을 것이다. 65 그대로면 살아있는
        // 값을 건드리지 않았다는 뜻이다.
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("65");

        String expireAtKey = String.format("campaign:%d:expireAt", savedCampaign.getId());
        assertThat(redisTemplate.opsForValue().get(expireAtKey)).isNotNull();

        // redis(65) < db(70)는 트래픽이 계속돼 DB가 못 따라온 정상적인 시간차다 — 초과 발급
        // 위험이 없는 방향이므로 자체 점검은 이걸 위험으로 보고하면 안 된다.
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("부분 복구 자체 점검: Redis 재고가 DB보다 많으면(되살아난 방향) 실제로 탐지한다.")
    void recoverMissingCache_ScopedCheck_DetectsRedisExceedingDb() {
        // given
        LocalDateTime now = LocalDateTime.now();
        Campaign campaign =
                Campaign.builder()
                        .name("삿포로 노선 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("ICN-CTS")
                        .fareClass("Y")
                        .totalStock(100)
                        .build();
        stock.decreaseStock(30); // DB 기준 잔여 70
        CampaignStock savedStock = campaignStockRepository.save(stock);

        // Redis 쪽 재고가 DB(70)보다 더 많이(80) 남아있는, 재고가 부당하게 되살아난 위험한 상황을
        // 흉내낸다 — SETNX는 이미 있는 이 값을 건드리지 않으므로 오염이 그대로 남는다.
        String stockKey = String.format("stock:%d", savedStock.getId());
        redisTemplate.opsForValue().set(stockKey, "80");

        // when
        List<String> mismatches =
                campaignCacheWarmupService.recoverMissingCache(savedCampaign.getId());

        // then
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("80"); // SETNX가 안 고침
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0))
                .contains("stockId=" + savedStock.getId(), "redis=80", "db=70");
    }

    @Test
    @DisplayName("부분 복구: issued Set이 없으면 DB 기준으로 SADD만으로 채운다 (RENAME 없음).")
    void recoverMissingCache_RebuildsIssuedSetAdditively() {
        // given
        LocalDateTime now = LocalDateTime.now();
        Campaign campaign =
                Campaign.builder()
                        .name("싱가포르 노선 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId("ICN-SIN")
                        .fareClass("Y")
                        .totalStock(50)
                        .build();
        CampaignStock savedStock = campaignStockRepository.save(stock);

        jdbcTemplate.update(
                "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)",
                200L,
                "recover-user200@test.com",
                "유저200");
        Coupon coupon =
                Coupon.issue(
                        4001L,
                        200L,
                        savedCampaign.getId(),
                        savedStock.getId(),
                        savedCampaign.getOpenAt(),
                        savedCampaign.getExpireAt());
        couponRepository.save(coupon);

        // issued Set 자체가 Redis에 없는 상태(유실) — 다른 키는 아무것도 준비하지 않는다.

        // when
        List<String> mismatches =
                campaignCacheWarmupService.recoverMissingCache(savedCampaign.getId());

        // then
        String issuedKey = String.format("issued:%d", savedCampaign.getId());
        Set<String> issuedUsers = redisTemplate.opsForSet().members(issuedKey);
        assertThat(issuedUsers).containsExactly("200");

        // 총재고 50, 발급 0건 상태로 채워졌으므로(DB remainingStock=50) 자체 점검은 통과한다.
        assertThat(mismatches).isEmpty();
    }
}
