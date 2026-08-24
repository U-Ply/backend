package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
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
        ConcurrentLinkedQueue<IssueFailReason> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < requests; i++) {
            long userId = 40001L + i;
            pool.submit(
                    () -> {
                        ready.countDown();
                        try {
                            start.await();
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

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(failures).hasSize(70);
        assertThat(failures).allMatch(reason -> reason == IssueFailReason.OUT_OF_STOCK);

        // V3 는 컨슈머가 DB 에 쓸 때까지 아무것도 확정되지 않는다. DB 쪽 단언은 전부
        // 이 블록 안에 있어야 한다 — 쿠폰 행은 들어왔는데 재고 반영이 한 박자 늦으면
        // 밖에 둔 단언이 간헐적으로 깨진다.
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            assertThat(fixture.couponCount()).isEqualTo(30);
                            assertThat(fixture.historyCount()).isEqualTo(30);
                            assertThat(fixture.remaining()).isZero();
                        });

        // Redis 쪽은 Lua 가 발급 시점에 동기로 쓰므로 대기 없이 확정이다.
        assertThat(redis.opsForValue().get("stock:" + CouponIntegrationFixture.STOCK_ID))
                .isEqualTo("0");
        assertThat(redis.opsForSet().size("issued:" + CouponIntegrationFixture.CAMPAIGN_ID))
                .isEqualTo(30L);

        // 정착이 끝난 뒤에 돌린다. 대사 배치가 Kafka 정착 여부를 확인하므로,
        // 컨슈머가 아직 먹는 중이면 REC-01 이 SKIPPED_NOT_SETTLED 로 빠지고
        // 회차 판정이 "불완전" 이 된다.
        RoundReportAssert.assertPassed(reportWriter.writeReport("V3"), "V3");
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
                                        new NewTopic("coupon-issued", 3, (short) 1),
                                        new NewTopic("coupon-issued.DLT", 3, (short) 1)))
                        .all()
                        .get();
            } catch (Exception e) {
                // Testcontainers Kafka 는 컨테이너 단위로 재사용되므로 이미 존재하는
                // 토픽은 그대로 사용한다.
                Throwable cause = e.getCause();
                if (!(cause instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }
}
