package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.operation.reconciliation.service.KafkaSettlementChecker;
import java.time.Duration;
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
            "coupon.issue.strategy=LUA_SCRIPT",
            "coupon.save.strategy=kafka",
            "coupon.idempotency.enabled=false"
        })
class V3KafkaIssueIntegrationTest extends IntegrationTestContainers {

    /**
     * V3 초기 재고.
     */
    private static final int STOCK = 10;

    /**
     * V3 동시 발급 요청 수.
     */
    private static final int REQUESTS = 30;

    @Autowired
    CouponService couponService;

    @Autowired
    CouponIntegrationFixture fixture;

    @Autowired
    CampaignCacheWarmupService warmupService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RoundReportWriter reportWriter;

    @Autowired
    KafkaSettlementChecker kafkaSettlementChecker;

    @BeforeEach
    void setUp() {

        /*
         * 앞선 테스트에서 남은 Redis 상태가 현재 회차에 영향을 주지 않도록
         * warmup 전에 Redis를 비운다.
         *
         * 특히 V2/V3는 동일한 campaign/stock key를 사용하므로
         * warmup 이후에 flushDb()를 실행하면 안 된다.
         */
        redis.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        /*
         * 이전 회차의 DB fixture를 정리한다.
         */
        fixture.reset();

        /*
         * V1/V2와 동일한 기준 부하 조건을 사용한다.
         *
         * 초기 재고: 10
         * 동시 요청: 30
         */
        fixture.createCampaign(STOCK);
        fixture.createUsers(REQUESTS, 40001L);

        /*
         * Kafka topic은 IntegrationTestContainers에서
         * Kafka 컨테이너 시작 직후 생성 및 검증된다.
         *
         * coupon-issued      -> 3 partitions
         * coupon-issued.DLT  -> 3 partitions
         *
         * 따라서 V3에서는 topic을 직접 생성하지 않는다.
         */

        /*
         * Lua 발급 경로에서 사용할 campaign/stock/issued Redis key를
         * 현재 fixture 데이터 기준으로 준비한다.
         */
        warmupService.warmupCampaign(
                CouponIntegrationFixture.CAMPAIGN_ID);
    }

    @AfterEach
    void tearDown() {

        /*
         * 다음 테스트가 이전 회차의 Redis 상태를 보지 않도록 정리한다.
         */
        redis.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        /*
         * 테스트 fixture도 정리한다.
         */
        fixture.reset();
    }

    @Test
    void V3는_Lua_Kafka_DB_전체경로에서_정확히_10건을_최종_정착시킨다()
            throws Exception {

        ExecutorService pool =
                Executors.newFixedThreadPool(REQUESTS);

        CountDownLatch ready =
                new CountDownLatch(REQUESTS);

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch done =
                new CountDownLatch(REQUESTS);

        ConcurrentLinkedQueue<IssueFailReason> failures =
                new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<Throwable> unexpected =
                new ConcurrentLinkedQueue<>();

        try {

            /*
             * 모든 worker를 먼저 준비시킨 뒤
             * 하나의 start latch로 동시에 발급을 시작한다.
             */
            for (int i = 0; i < REQUESTS; i++) {

                long userId = 40001L + i;

                pool.submit(
                        () -> {

                            ready.countDown();

                            try {

                                /*
                                 * 모든 worker가 준비된 뒤
                                 * 동시에 시작하도록 한다.
                                 */
                                assertThat(
                                                start.await(
                                                        10,
                                                        TimeUnit.SECONDS))
                                        .as(
                                                "동시 발급 시작 신호를 10초 안에 받지 못했습니다.")
                                        .isTrue();

                                couponService.issue(
                                        "integration-v3-" + userId,
                                        new CouponIssueRequest(
                                                userId,
                                                CouponIntegrationFixture.CAMPAIGN_ID,
                                                CouponIntegrationFixture.ROUTE,
                                                CouponIntegrationFixture.FARE));

                            } catch (CouponIssueException e) {

                                /*
                                 * 재고 소진에 따른 정상적인 도메인 거절은
                                 * failures에 기록한다.
                                 */
                                failures.add(e.getReason());

                            } catch (Throwable t) {

                                /*
                                 * 예상하지 못한 예외는 별도로 기록한다.
                                 */
                                unexpected.add(t);

                            } finally {

                                done.countDown();
                            }
                        });
            }

            /*
             * 모든 worker가 준비될 때까지 기다린다.
             */
            assertThat(
                            ready.await(
                                    10,
                                    TimeUnit.SECONDS))
                    .as(
                            "모든 발급 작업이 10초 안에 준비되지 않았습니다.")
                    .isTrue();

            /*
             * 모든 worker를 동시에 출발시킨다.
             */
            start.countDown();

            /*
             * 전체 요청이 제한 시간 안에 종료되어야 한다.
             */
            assertThat(
                            done.await(
                                    60,
                                    TimeUnit.SECONDS))
                    .as(
                            "모든 발급 요청이 60초 안에 종료되지 않았습니다. requests=%d",
                            REQUESTS)
                    .isTrue();

            /*
             * OUT_OF_STOCK 이외의 예상하지 못한 예외는 허용하지 않는다.
             */
            assertThat(unexpected)
                    .as("V3에서 예상하지 못한 예외가 발생했습니다.")
                    .isEmpty();

            /*
             * 재고 10개에 요청 30건이므로:
             *
             * 성공 = 10
             * 실패 = 20
             * 실패 사유 = OUT_OF_STOCK
             */
            assertThat(failures)
                    .as("재고 소진으로 거절된 요청 수가 예상과 다릅니다.")
                    .hasSize(REQUESTS - STOCK);

            assertThat(failures)
                    .as("V3의 정상 거절 사유는 OUT_OF_STOCK이어야 합니다.")
                    .allMatch(
                            reason ->
                                    reason == IssueFailReason.OUT_OF_STOCK);

            /*
             * Lua에서 Redis 재고를 차감한 뒤
             * Kafka를 통해 DB settlement가 비동기적으로 수행된다.
             *
             * 따라서 coupon.issue() 호출이 모두 끝난 시점과
             * DB 최종 정착 시점은 다를 수 있다.
             *
             * Kafka Consumer가 DB에 coupon/history/remaining을
             * 반영할 때까지 기다린다.
             */
            await()
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {

                                assertThat(fixture.couponCount())
                                        .as("최종 DB 쿠폰 수")
                                        .isEqualTo(STOCK);

                                assertThat(fixture.historyCount())
                                        .as("최종 DB 발급 이력 수")
                                        .isEqualTo(STOCK);

                                assertThat(fixture.remaining())
                                        .as("최종 DB 잔여 재고")
                                        .isZero();
                            });

            /*
             * DB 값이 맞는 것과 Kafka settlement가 완전히 끝난 것은
             * 별개의 문제다.
             *
             * Consumer lag과 DLT까지 정착된 뒤
             * verification report를 실행한다.
             */
            await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    kafkaSettlementChecker
                                                            .check()
                                                            .settled())
                                            .as(
                                                    "Kafka consumer lag/DLT가 아직 정착되지 않았습니다.")
                                            .isTrue());

            /*
             * Lua는 발급 시점에 Redis 재고를 원자적으로 차감한다.
             *
             * Kafka settlement와 관계없이 Redis stock은 이미 0이어야 한다.
             */
            assertThat(
                            redis.opsForValue()
                                    .get(
                                            "stock:"
                                                    + CouponIntegrationFixture.STOCK_ID))
                    .as("Lua 실행 후 Redis 재고")
                    .isEqualTo("0");

            /*
             * Lua issued set에도 성공한 사용자 10명이 기록되어야 한다.
             */
            assertThat(
                            redis.opsForSet()
                                    .size(
                                            "issued:"
                                                    + CouponIntegrationFixture.CAMPAIGN_ID))
                    .as("Lua issued set 크기")
                    .isEqualTo((long) STOCK);

            /*
             * DB와 Kafka settlement가 모두 정착된 이후에
             * 최종 verification report를 생성한다.
             *
             * settlement 전에 report를 실행하면 REC-01이
             * SKIPPED_NOT_SETTLED가 될 수 있으므로 순서가 중요하다.
             */
            RoundReportAssert.assertPassed(
                    reportWriter.writeReport("V3"),
                    "V3");

        } finally {

            /*
             * 테스트가 성공/실패하더라도 worker pool은 반드시 정리한다.
             */
            pool.shutdownNow();
        }
    }
}