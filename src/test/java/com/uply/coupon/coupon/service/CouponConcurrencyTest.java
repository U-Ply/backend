package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.LocalDateTime;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

class CouponConcurrencyTest {

    @Nested
    @SpringBootTest(properties = {"coupon.save.strategy=sync-db"})
    @DisplayName("1. 동기 DB 저장 전략 (sync-db) 동시성 테스트")
    class SyncDbStrategyConcurrencyTest {

        @Autowired private CouponService couponService;
        @Autowired private CampaignCacheWarmupService warmupService;
        @Autowired private CampaignRepository campaignRepository;
        @Autowired private CampaignStockRepository campaignStockRepository;
        @Autowired private CouponRepository couponRepository;
        @Autowired private CouponHistoryRepository couponHistoryRepository;
        @Autowired private StringRedisTemplate redisTemplate;
        @Autowired private JdbcTemplate jdbcTemplate;

        private Long campaignId;
        private Long stockId;
        private final String routeId = "ICN-NRT";
        private final String fareClass = "Y";

        @BeforeEach
        void setUp() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LocalDateTime now = LocalDateTime.now();

            // 1. 유저 사전 데이터 생성 (FK 참조용)
            for (long i = 1; i <= 30; i++) {
                jdbcTemplate.update(
                        "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE user_id = user_id",
                        i,
                        "user" + i + "@test.com",
                        "유저" + i);
            }

            // 2. 캠페인 및 재고 데이터 생성 (테스트 클래스에 @Transactional이 없으므로 자동 커밋됨)
            Campaign campaign =
                    campaignRepository.save(
                            Campaign.builder()
                                    .name("동기 DB 선착순 10명 쿠폰")
                                    .openAt(now.minusHours(1))
                                    .expireAt(now.plusDays(7))
                                    .build());
            this.campaignId = campaign.getId();

            CampaignStock stock =
                    campaignStockRepository.save(
                            CampaignStock.builder()
                                    .campaign(campaign)
                                    .routeId(routeId)
                                    .fareClass(fareClass)
                                    .totalStock(10)
                                    .build());
            this.stockId = stock.getId();

            // 3. Redis 캐시 워밍업
            warmupService.warmupCampaign(this.campaignId);
        }

        @AfterEach
        void tearDown() {
            // @Transactional이 없으므로 격리를 위해 명시적 DB/Redis CleanUp 수행
            couponHistoryRepository.deleteAllInBatch();
            couponRepository.deleteAllInBatch();
            campaignStockRepository.deleteAllInBatch();
            campaignRepository.deleteAllInBatch();
            jdbcTemplate.execute("DELETE FROM users");

            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        @DisplayName("동기 DB 저장: 30명 동시 요청 시 정확히 10명 성공, DB 10건 저장, Redis 재고 0개")
        void issueCoupon_SyncDb_Concurrency_Success() throws InterruptedException {
            // given
            int totalRequests = 30;
            ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
            CountDownLatch readyLatch = new CountDownLatch(totalRequests);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalRequests);

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            Queue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

            long beforeCount = couponRepository.count();
            long beforeHistoryCount = couponHistoryRepository.count();

            // when
            for (long userId = 1; userId <= totalRequests; userId++) {
                final long currentUserId = userId;
                executorService.submit(
                        () -> {
                            try {
                                readyLatch.countDown();
                                startLatch.await();

                                CouponIssueRequest request =
                                        new CouponIssueRequest(
                                                currentUserId, campaignId, routeId, fareClass);
                                String idempotencyKey = "sync-idempotency-key-" + currentUserId;

                                couponService.issue(idempotencyKey, request);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                exceptions.add(e);
                                failCount.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            readyLatch.await();
            startLatch.countDown();
            doneLatch.await();
            executorService.shutdown();

            // then
            // 1. 성공/실패 요청 수 검증 (재고 10개 기준: 성공 10건, 재고소진 실패 20건)
            assertThat(successCount.get()).isEqualTo(10);
            assertThat(failCount.get()).isEqualTo(20);

            // 2. RDB 동기 저장 결과 정량 검증
            assertThat(couponRepository.count()).isEqualTo(beforeCount + 10L);
            assertThat(couponHistoryRepository.count()).isEqualTo(beforeHistoryCount + 10L);

            // 3. Redis 잔여 재고 및 발급 완료 유저 Set 검증
            String remainStock = redisTemplate.opsForValue().get("stock:" + stockId);
            assertThat(Long.parseLong(remainStock)).isEqualTo(0L);
            assertThat(redisTemplate.opsForSet().size("issued:" + campaignId)).isEqualTo(10L);

            // 4. 예외 발생 내역 검증 (예상치 못한 DB Lock Timeout이나 System Crash 예외가 없는지 확인)
            boolean hasUnexpectedException =
                    exceptions.stream()
                            .anyMatch(e -> e instanceof PessimisticLockingFailureException);
            assertThat(hasUnexpectedException).isFalse();
        }
    }

    @Nested
    @SpringBootTest(properties = {"coupon.save.strategy=kafka"})
    @Transactional
    @DisplayName("2. 카프카 비동기 저장 전략 (kafka) 동시성 테스트")
    class KafkaStrategyConcurrencyTest {

        @Autowired private CouponService couponService;
        @Autowired private CampaignCacheWarmupService warmupService;
        @Autowired private CampaignRepository campaignRepository;
        @Autowired private CampaignStockRepository campaignStockRepository;
        @Autowired private CouponRepository couponRepository;
        @Autowired private StringRedisTemplate redisTemplate;
        @Autowired private JdbcTemplate jdbcTemplate;

        @MockBean private KafkaTemplate<String, Object> kafkaTemplate;

        private Long campaignId;
        private Long stockId;
        private final String routeId = "ICN-NRT";
        private final String fareClass = "Y";

        @BeforeEach
        void setUp() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LocalDateTime now = LocalDateTime.now();

            for (long i = 1; i <= 30; i++) {
                jdbcTemplate.update(
                        "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE user_id = user_id",
                        i,
                        "user" + i + "@test.com",
                        "유저" + i);
            }

            Campaign campaign =
                    campaignRepository.save(
                            Campaign.builder()
                                    .name("카프카 비동기 선착순 10명 쿠폰")
                                    .openAt(now.minusHours(1))
                                    .expireAt(now.plusDays(7))
                                    .build());
            this.campaignId = campaign.getId();

            CampaignStock stock =
                    campaignStockRepository.save(
                            CampaignStock.builder()
                                    .campaign(campaign)
                                    .routeId(routeId)
                                    .fareClass(fareClass)
                                    .totalStock(10)
                                    .build());
            this.stockId = stock.getId();

            warmupService.warmupCampaign(this.campaignId);
        }

        @AfterEach
        void tearDown() {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        @DisplayName("카프카 비동기 저장: 30명 동시 요청 시 정확히 10명 성공, Kafka 메시지 10회 발행, DB 저장 0건")
        void issueCoupon_Kafka_Concurrency_Success() throws InterruptedException {
            // given
            int totalRequests = 30;
            ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
            CountDownLatch readyLatch = new CountDownLatch(totalRequests);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalRequests);

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            long beforeCount = couponRepository.count();

            // when
            for (long userId = 1; userId <= totalRequests; userId++) {
                final long currentUserId = userId;
                executorService.submit(
                        () -> {
                            try {
                                readyLatch.countDown();
                                startLatch.await();

                                CouponIssueRequest request =
                                        new CouponIssueRequest(
                                                currentUserId, campaignId, routeId, fareClass);
                                String idempotencyKey = "kafka-idempotency-key-" + currentUserId;

                                couponService.issue(idempotencyKey, request);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                failCount.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            readyLatch.await();
            startLatch.countDown();
            doneLatch.await();
            executorService.shutdown();

            // then
            // 1. 성공/실패 수량 검증
            assertThat(successCount.get()).isEqualTo(10);
            assertThat(failCount.get()).isEqualTo(20);

            // 2. Kafka 이벤트 발행 건수 검증 (정확히 성공한 10건만 발행되어야 함)
            then(kafkaTemplate)
                    .should(times(10))
                    .send(eq("coupon-issued"), anyString(), any(CouponIssuedEvent.class));

            // 3. RDB 비동기 저장 상태 검증 (요청 시점에는 DB 저장 0건이어야 함)
            assertThat(couponRepository.count()).isEqualTo(beforeCount);

            // 4. Redis 잔여 재고 및 발급자 Set 검증
            String remainStock = redisTemplate.opsForValue().get("stock:" + stockId);
            assertThat(Long.parseLong(remainStock)).isEqualTo(0L);
            assertThat(redisTemplate.opsForSet().size("issued:" + campaignId)).isEqualTo(10L);
        }
    }
}
