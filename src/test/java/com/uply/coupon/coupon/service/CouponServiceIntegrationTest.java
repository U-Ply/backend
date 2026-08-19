package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.coupon.domain.CouponStatus;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.dto.response.CouponIssueResponse;
import com.uply.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

class CouponServiceIntegrationTest {

    @Nested
    @SpringBootTest(
            properties = {
                "coupon.save.strategy=sync-db",
                "coupon.issue.strategy=LUA_SCRIPT",
                "spring.datasource.hikari.maximum-pool-size=20"
            })
    @Transactional
    @DisplayName("1. 동기 DB 저장 전략 (sync-db) 통합 테스트")
    class SyncDbStrategyTest {

        @Autowired private CouponService couponService;
        @Autowired private CampaignCacheWarmupService warmupService;
        @Autowired private CampaignRepository campaignRepository;
        @Autowired private CampaignStockRepository campaignStockRepository;
        @Autowired private CouponRepository couponRepository;
        @Autowired private StringRedisTemplate redisTemplate;
        @Autowired JdbcTemplate jdbcTemplate;

        private Long userId = 100L;
        private Long campaignId;
        private final String routeId = "ICN-NRT";
        private final String fareClass = "Y";

        @BeforeEach
        void setUp() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LocalDateTime now = LocalDateTime.now();

            // 1. 엔티티 없이 SQL로 users 테이블에 FK 충족용 더미 데이터 삽입
            jdbcTemplate.update(
                    "INSERT INTO users (user_id, email, name) VALUES (?,?,?) ON DUPLICATE KEY UPDATE user_id = user_id",
                    userId,
                    "user@test.com",
                    "테스트유저");

            // 1. RDB 캠페인/재고 데이터 생성
            Campaign campaign =
                    campaignRepository.save(
                            Campaign.builder()
                                    .name("동기 DB 발급 테스트 캠페인")
                                    .openAt(now.minusHours(1))
                                    .expireAt(now.plusDays(7))
                                    .build());
            this.campaignId = campaign.getId();

            campaignStockRepository.save(
                    CampaignStock.builder()
                            .campaign(campaign)
                            .routeId(routeId)
                            .fareClass(fareClass)
                            .totalStock(10)
                            .build());

            // 2. Redis 캐시 웜업 (RedisStockIdLookup용 Key 생성)
            warmupService.warmupCampaign(this.campaignId);
        }

        @AfterEach
        void tearDown() {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        @DisplayName(
                "RedisStockIdLookup으로 stockId를 조회하고, SyncMysqlSaveStrategy를 통해 DB에 동기적으로 즉시 저장된다.")
        void issueCoupon_SyncDb_Success() {
            // given
            CouponIssueRequest request =
                    new CouponIssueRequest(userId, campaignId, routeId, fareClass);
            String idempotencyKey = "sync-db-idempotency-key-100";
            long beforeCount = couponRepository.count();

            // when
            CouponIssueResponse response = couponService.issue(idempotencyKey, request);

            // then
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 1. RDB 동기 저장 검증 (즉시 1건 생성)
            long count = couponRepository.count();
            assertThat(count).isEqualTo(beforeCount + 1);

            // 2. Redis 잔여 재고 검증 (10 -> 9 차감)
            String stockId =
                    redisTemplate
                            .opsForValue()
                            .get("stockId:" + campaignId + ":" + routeId + ":" + fareClass);
            assertThat(stockId).isNotNull();
            String remainStock = redisTemplate.opsForValue().get("stock:" + stockId);
            assertThat(Long.parseLong(remainStock)).isEqualTo(9L);
        }
    }

    @Nested
    @SpringBootTest(properties = {"coupon.save.strategy=kafka", "coupon.issue.strategy=LUA_SCRIPT"})
    @Transactional
    @DisplayName("2. 카프카 비동기 저장 전략 (kafka) 통합 테스트")
    class KafkaStrategyTest {

        @Autowired private CouponService couponService;
        @Autowired private CampaignCacheWarmupService warmupService;
        @Autowired private CampaignRepository campaignRepository;
        @Autowired private CampaignStockRepository campaignStockRepository;
        @Autowired private CouponRepository couponRepository;
        @Autowired private StringRedisTemplate redisTemplate;
        @Autowired JdbcTemplate jdbcTemplate;

        @MockBean private KafkaTemplate<String, String> kafkaTemplate;

        private Long userId = 100L;
        private Long campaignId;
        private final String routeId = "ICN-NRT";
        private final String fareClass = "Y";

        @BeforeEach
        void setUp() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LocalDateTime now = LocalDateTime.now();

            // ★ [추가] MockBean인 kafkaTemplate.send() 호출 시 CompletableFuture를 반환하도록 스터빙
            given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .willReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            
            // 1. 엔티티 없이 SQL로 users 테이블에 FK 충족용 더미 데이터 삽입
            jdbcTemplate.update(
                    "INSERT INTO users (user_id, email, name) VALUES (?,?,?) ON DUPLICATE KEY UPDATE user_id = user_id",
                    userId,
                    "user@test.com",
                    "테스트유저");

            Campaign campaign =
                    campaignRepository.save(
                            Campaign.builder()
                                    .name("카프카 비동기 발급 테스트 캠페인")
                                    .openAt(now.minusHours(1))
                                    .expireAt(now.plusDays(7))
                                    .build());
            this.campaignId = campaign.getId();

            campaignStockRepository.save(
                    CampaignStock.builder()
                            .campaign(campaign)
                            .routeId(routeId)
                            .fareClass(fareClass)
                            .totalStock(10)
                            .build());

            warmupService.warmupCampaign(this.campaignId);
        }

        @AfterEach
        void tearDown() {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        @DisplayName(
                "RedisStockIdLookup으로 stockId를 조회하고, CouponIssuedProducer를 통해 Kafka 이벤트가 정상 발행된다.")
        void issueCoupon_Kafka_Success() {
            // given
            CouponIssueRequest request =
                    new CouponIssueRequest(userId, campaignId, routeId, fareClass);
            String idempotencyKey = "kafka-idempotency-key-200";
            long beforeCount = couponRepository.count();

            // when
            CouponIssueResponse response = couponService.issue(idempotencyKey, request);

            // then
            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(CouponStatus.ISSUED);

            // 1. Kafka 이벤트 발행 검증 (KafkaTemplate.send 호출 확인)
            then(kafkaTemplate)
                    .should(times(1))
                    .send(eq("coupon-issued"), anyString(), anyString());

            // 2. RDB 검증 (비동기 처리이므로 요청 시점에는 DB에 0건 저장되어야 함)
            long count = couponRepository.count();
            assertThat(count).isEqualTo(beforeCount);

            // 3. Redis 잔여 재고 검증 (10 -> 9 차감)
            String stockId =
                    redisTemplate
                            .opsForValue()
                            .get("stockId:" + campaignId + ":" + routeId + ":" + fareClass);
            assertThat(stockId).isNotNull();
            String remainStock = redisTemplate.opsForValue().get("stock:" + stockId);
            assertThat(Long.parseLong(remainStock)).isEqualTo(9L);
        }

        @Test
        @DisplayName("카프카 비동기 저장: 동일 유저가 10번 동시 요청 시 정확히 1번만 성공(중복 발급 방지)")
        void issueCoupon_DuplicateRequest_OnlyOneSuccess() throws InterruptedException {
            // given
            int concurrentRequests = 10;
            long sameUserId = 999L; // 동일한 유저
            String sameIdempotencyKey = "same-key-999";

            ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequests);
            CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            // when
            for (int i = 0; i < concurrentRequests; i++) {
                executorService.submit(
                        () -> {
                            try {
                                CouponIssueRequest request =
                                        new CouponIssueRequest(
                                                sameUserId, campaignId, routeId, fareClass);
                                couponService.issue(sameIdempotencyKey, request);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                failCount.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            doneLatch.await();
            executorService.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(1); // 1번만 성공
            assertThat(failCount.get()).isEqualTo(9); // 나머지 9번은 중복 요청으로 실패

            then(kafkaTemplate)
                    .should(times(1))
                    .send(eq("coupon-issued"), anyString(), anyString());
        }
    }
}
