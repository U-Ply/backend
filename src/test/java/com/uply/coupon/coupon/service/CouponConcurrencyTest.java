package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * [ Redis Lua Level 1 테스트 ] 재고 10개 캠페인에 30명 동시 발급 요청
 *
 * <p>1. 성공=10/실패=20 2. 남은재고=0 3. 발급자 Set 크기 = 10
 */
@Transactional
@SpringBootTest(properties = {
	    "coupon.save.strategy=sync-db",
	    "spring.datasource.hikari.maximum-pool-size=35",
	    "spring.datasource.hikari.connection-timeout=10000"
	})
class CouponConcurrencyTest {

    @Autowired private CouponService couponService;

    @Autowired private CampaignCacheWarmupService warmupService;

    @Autowired private CampaignRepository campaignRepository;

    @Autowired private CampaignStockRepository campaignStockRepository;

    @Autowired private StringRedisTemplate redisTemplate;

    private Long campaignId;
    private Long stockId;
    private final String routeId = "ICN-NRT";
    private final String fareClass = "Y";

    @BeforeEach
    void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        LocalDateTime now = LocalDateTime.now();

        // 1. RDB 데이터 준비 (재고 10개 설정)
        Campaign campaign =
                Campaign.builder()
                        .name("선착순 10명 할인 쿠폰")
                        .openAt(now.minusHours(1))
                        .expireAt(now.plusDays(7))
                        .build();
        Campaign savedCampaign = campaignRepository.save(campaign);
        this.campaignId = savedCampaign.getId();

        CampaignStock stock =
                CampaignStock.builder()
                        .campaign(savedCampaign)
                        .routeId(routeId)
                        .fareClass(fareClass)
                        .totalStock(10)
                        .build();
        CampaignStock savedStock = campaignStockRepository.save(stock);
        this.stockId = savedStock.getId();

        // 2. Redis 캐시 웜업 실행 (stock:100, stockId:1:ICN-NRT:Y 생성)
        warmupService.warmupCampaign(this.campaignId);
    }

    @AfterEach
    void tearDown() {
        // Redis 전체 키 삭제
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("재고 10개 캠페인에 30명 동시 발급 요청 시, 정확히 10명만 성공하고 Redis 재고는 0이 된다.")
    void issueCoupon_Concurrency_Success() throws InterruptedException {
        // given
        int totalRequests = 30;
        ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        for (long userId = 1; userId <= totalRequests; userId++) {
            final long currentUserId = userId;
            executorService.submit(
                    () -> {
                        try {
                            readyLatch.countDown();
                            startLatch.await();

                            // 각 사용자별 고유 요청 DTO 및 멱등성 키 생성
                            CouponIssueRequest request =
                                    new CouponIssueRequest(
                                            currentUserId, campaignId, routeId, fareClass);
                            String idempotencyKey = "idempotency-key-user-" + currentUserId;

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
        startLatch.countDown(); // 30개 스레드 동시 실행
        doneLatch.await();

        // then
        // 1. 성공/실패 응답 수량 검증
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failCount.get()).isEqualTo(20);

        // 2. Redis 잔여 재고 수량 검증 (0개)
        String remainStock = redisTemplate.opsForValue().get("stock:" + stockId);
        assertThat(Long.parseLong(remainStock)).isEqualTo(0);

        // 3. 중복 발급 방지 Set 크기 검증 (10명)
        Long issuedUserCount = redisTemplate.opsForSet().size("issued:" + campaignId);
        assertThat(issuedUserCount).isEqualTo(10);
    }
}
