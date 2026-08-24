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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
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

    @Autowired CouponService couponService;

    @Autowired CouponIntegrationFixture fixture;

    @Autowired CampaignCacheWarmupService warmupService;

    @Autowired StringRedisTemplate redis;

    @Autowired RoundReportWriter reportWriter;

    @Autowired KafkaSettlementChecker kafkaSettlementChecker;

    @BeforeEach
    void setUp() throws Exception {
        // 청소는 앞에서 한다. 뒤에서만 하면 앞선 클래스가 남긴 키 때문에 이 테스트가
        // 자기 잘못이 아닌 이유로 깨진다. warmup 보다 반드시 먼저다.
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        fixture.reset();

        fixture.createCampaign(30);
        fixture.createUsers(100, 40001L);

        createIssueTopic();

        warmupService.warmupCampaign(CouponIntegrationFixture.CAMPAIGN_ID);
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void V3는_Lua_Kafka_DB_전체경로에서_정확히_발급량을_정착시킨다() throws Exception {
        int requests = 100;

        ExecutorService pool = Executors.newFixedThreadPool(requests);

        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);

        ConcurrentLinkedQueue<IssueFailReason> failures =
                new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<Throwable> unexpected =
                new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < requests; i++) {
                long userId = 40001L + i;

                pool.submit(
                        () -> {
                            ready.countDown();

                            try {
                                assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                        .as("동시 발급 시작 신호를 10초 안에 받지 못했습니다.")
                                        .isTrue();

                                couponService.issue(
                                        "integration-v3-" + userId,
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

            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("모든 발급 작업이 10초 안에 준비되지 않았습니다.")
                    .isTrue();

            start.countDown();

            assertThat(done.await(60, java.util.concurrent.TimeUnit.SECONDS))
                    .as("모든 발급 작업이 60초 안에 종료되지 않았습니다.")
                    .isTrue();

            assertThat(unexpected).isEmpty();

            assertThat(failures).hasSize(70);
            assertThat(failures)
                    .allMatch(reason -> reason == IssueFailReason.OUT_OF_STOCK);

            /*
             * V3는 Kafka Consumer가 DB에 저장할 때까지 최종 정착되지 않는다.
             *
             * 먼저 DB의 쿠폰/이력/재고가 반영되는 것을 기다린다.
             */
            await()
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                assertThat(fixture.couponCount()).isEqualTo(30);
                                assertThat(fixture.historyCount()).isEqualTo(30);
                                assertThat(fixture.remaining()).isZero();
                            });

            /*
             * Kafka Consumer의 처리와 offset commit, DLT 상태까지 정착된 후
             * verification report를 실행해야 한다.
             *
             * KafkaSettlementChecker.check()는 현재 애플리케이션의 실제
             * settlement 상태를 반환하며, settled()는 lag == 0 && DLT == 0인
             * 경우에만 true가 된다.
             */
            await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(
                            () ->
                                    assertThat(kafkaSettlementChecker.check().settled())
                                            .as("Kafka consumer lag/DLT가 아직 정착되지 않았습니다.")
                                            .isTrue());

            /*
             * Redis 쪽은 Lua가 발급 시점에 동기로 쓰므로 Kafka 정착을 기다릴
             * 필요 없이 여기서 바로 검증한다.
             */
            assertThat(
                            redis.opsForValue()
                                    .get("stock:" + CouponIntegrationFixture.STOCK_ID))
                    .isEqualTo("0");

            assertThat(
                            redis.opsForSet()
                                    .size(
                                            "issued:"
                                                    + CouponIntegrationFixture.CAMPAIGN_ID))
                    .isEqualTo(30L);

            /*
             * DB 반영 + Kafka settlement가 모두 끝난 뒤에야 검증 리포트를 실행한다.
             *
             * 이 순서가 중요하다.
             * settlement 전에 실행하면 REC-01이 SKIPPED_NOT_SETTLED가 되어
             * 회차가 INCOMPLETE가 될 수 있다.
             */
            RoundReportAssert.assertPassed(
                    reportWriter.writeReport("V3"),
                    "V3");

        } finally {
            pool.shutdownNow();
        }
    }

    private void createIssueTopic() throws Exception {
        try (AdminClient admin =
                AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {

            try {
                admin.createTopics(
                                List.of(
                                        new NewTopic(
                                                "coupon-issued",
                                                3,
                                                (short) 1),
                                        new NewTopic(
                                                "coupon-issued.DLT",
                                                3,
                                                (short) 1)))
                        .all()
                        .get();

            } catch (Exception e) {
                // Testcontainers Kafka는 컨테이너 단위로 재사용되므로 이미 존재하는
                // 토픽은 그대로 사용한다.
                Throwable cause = e.getCause();

                if (!(cause instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }
}