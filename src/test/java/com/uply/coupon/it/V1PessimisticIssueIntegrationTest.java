package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
        properties = {
            "coupon.issue.strategy=PESSIMISTIC_LOCK",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false"
        })
class V1PessimisticIssueIntegrationTest extends IntegrationTestContainers {

    @Autowired CouponService couponService;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired StringRedisTemplate redis;
    @Autowired RoundReportWriter reportWriter;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
        fixture.createCampaign(30);
        fixture.createUsers(100, 10001L);
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void 재고_30개에_100건_동시발급하면_정확히_30건만_성공한다() throws Exception {
        int requests = 100;

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);

        ConcurrentLinkedQueue<IssueFailReason> failures = new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < requests; i++) {
                long userId = 10001L + i;

                pool.submit(
                        () -> {
                            ready.countDown();

                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS))
                                        .as("동시 발급 시작 신호를 10초 안에 받지 못했습니다.")
                                        .isTrue();

                                couponService.issue(
                                        "integration-v1-" + userId,
                                        new CouponIssueRequest(
                                                userId,
                                                CouponIntegrationFixture.CAMPAIGN_ID,
                                                CouponIntegrationFixture.ROUTE,
                                                CouponIntegrationFixture.FARE));
                            } catch (CouponIssueException e) {
                                failures.add(e.getReason());
                            } catch (Throwable t) {
                                unexpected.add(t);
                            } finally {
                                done.countDown();
                            }
                        });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 발급 작업이 10초 안에 준비되지 않았습니다.")
                    .isTrue();

            start.countDown();

            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("모든 발급 작업이 60초 안에 종료되지 않았습니다.")
                    .isTrue();

            assertThat(unexpected).isEmpty();
            assertThat(failures).hasSize(70);
            assertThat(failures).allMatch(reason -> reason == IssueFailReason.OUT_OF_STOCK);

            assertThat(fixture.couponCount()).isEqualTo(30);
            assertThat(fixture.historyCount()).isEqualTo(30);
            assertThat(fixture.remaining()).isZero();

            // V1은 campaign/stock 조회를 DB에서 해야 한다. 발급 전에 Redis campaign/stockId
            // cache가 없어도 성공했으므로 Redis 의존성이 실제로 제거된 경로임을 확인한다.
            assertThat(redis.keys("campaign:" + CouponIntegrationFixture.CAMPAIGN_ID + ":*"))
                    .isEmpty();

            assertThat(redis.keys("stockId:" + CouponIntegrationFixture.CAMPAIGN_ID + ":*"))
                    .isEmpty();

            // 이 회차가 만든 데이터 위에서 검증 배치를 돌리고 리포트를 남긴다.
            // build/round-results/V1.md 가 이 회차의 산출물이다.
            RoundReportAssert.assertPassed(reportWriter.writeReport("V1"), "V1");

        } finally {
            pool.shutdownNow();
        }
    }
}
