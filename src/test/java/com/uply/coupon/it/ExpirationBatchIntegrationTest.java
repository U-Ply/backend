package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import com.uply.coupon.coupon.service.CouponService;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 만료 배치를 실제 발급 경로 위에서 검증한다.
 *
 * <p><b>쿠폰을 JDBC 로 직접 넣지 않는다.</b> 직접 INSERT 하면 만료 배치가 "우리가 손으로 만든 행" 을 바꾸는 것만 확인하게 되고, 발급이 만든 실제
 * 데이터에서도 같은지는 검사되지 않는다. 이력 유무, expire_at 상속, 재고 차감 같은 것들이 전부 픽스처의 성질이 되어버린다.
 *
 * <p><b>expire_at 을 과거로 옮기지도 않는다.</b> 그렇게 하면 issued_at &gt; expire_at 인 쿠폰이 생기고, 그건 INV-06(시각
 * 순서)·INV-11(캠페인 기간 내 발급)이 위반으로 잡아야 할 데이터를 검증하는 쪽에서 만들어내는 꼴이 된다. 캠페인 창을 짧게 두고 실제로 시간이 흐르게 한다.
 */
@SpringBootTest(
        properties = {
            // 만료 배치가 검증 대상이므로 발급 경로는 가장 단순하고 결정적인 것으로 고정한다.
            // Redis 워밍업이 필요 없고 정확히 재고만큼만 성공한다.
            "coupon.issue.strategy=PESSIMISTIC_LOCK",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false"
        })
@SpringBatchTest
class ExpirationBatchIntegrationTest extends IntegrationTestContainers {

    private static final int STOCK = 10;
    private static final int REQUESTS = 15;

    /** 캠페인이 열려 있는 시간. 발급은 1 초 안에 끝나므로 이 정도면 충분하다. */
    private static final int WINDOW_SEC = 5;

    private static final long USER_BASE = 60001L;

    @Autowired JobLauncherTestUtils jobLauncher;
    @Autowired JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("expirationJob")
    Job expirationJob;

    @Autowired CouponService couponService;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jobLauncher.setJob(expirationJob);

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
        fixture.createCampaign(STOCK);
        fixture.createUsers(REQUESTS, USER_BASE);

        // 캠페인 창을 WINDOW_SEC 로 좁힌다. createCampaign 은 2 시간짜리를 만든다.
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "UPDATE campaigns SET open_at = ?, expire_at = ? WHERE campaign_id = ?",
                now.minusSeconds(1),
                now.plusSeconds(WINDOW_SEC),
                CouponIntegrationFixture.CAMPAIGN_ID);
    }

    @AfterEach
    void tearDown() {
        jobRepositoryTestUtils.removeJobExecutions();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void 만료배치는_발급된_10건을_EXPIRED로_바꾸고_재고는_복구하지_않는다() throws Exception {
        // ── 1. 실제 발급 경로로 재고를 소진시킨다 ──
        IssueResult issue = issueAll();

        assertThat(issue.unexpected()).as("발급 중 예상 밖의 예외").isEmpty();
        assertThat(issue.success()).as("성공 건수").isEqualTo(STOCK);
        assertThat(issue.outOfStock()).as("재고 소진 건수").isEqualTo(REQUESTS - STOCK);

        assertThat(fixture.couponCount()).isEqualTo(STOCK);
        assertThat(fixture.historyCount()).isEqualTo(STOCK);
        assertThat(fixture.remaining()).isZero();

        // 만료 대상이 되려면 쿠폰이 캠페인 만료 시각을 물려받아야 한다 (INV-12).
        assertThat(couponsInheritingCampaignExpiry()).isEqualTo(STOCK);

        // ── 2. 캠페인 창이 닫힐 때까지 실제로 기다린다 ──
        awaitCampaignExpiry();

        // ── 3. 만료 배치 ──
        var execution =
                jobLauncher.launchJob(
                        new JobParametersBuilder()
                                .addLong("integrationRun", System.nanoTime())
                                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("EXPIRED")).as("만료된 쿠폰").isEqualTo(STOCK);
        assertThat(count("ISSUED")).as("만료 배치를 빠져나간 쿠폰").isZero();

        // test-plan 2.8 — 발급된 재고는 상태와 무관하게 영구 소진한다.
        assertThat(fixture.remaining()).as("만료가 재고를 되돌리면 안 된다").isZero();

        // 상태만 바꾸고 이력을 안 남기면 INV-04 가 잡아야 하지만, 여기서 먼저 드러나게 한다.
        assertThat(expiredHistoryCount()).as("만료 이력").isEqualTo(STOCK);
    }

    // ------------------------------------------------------------------

    private record IssueResult(
            int success, int outOfStock, ConcurrentLinkedQueue<Throwable> unexpected) {}

    private IssueResult issueAll() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUESTS);

        ConcurrentLinkedQueue<IssueFailReason> failures = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < REQUESTS; i++) {
            long userId = USER_BASE + i;
            pool.submit(
                    () -> {
                        ready.countDown();
                        try {
                            start.await();
                            couponService.issue(
                                    "integration-expiry-" + userId,
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
        boolean finished = done.await(WINDOW_SEC, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("발급이 캠페인 창(%d초) 안에 끝나지 않았다. 창을 늘려야 한다", WINDOW_SEC).isTrue();

        int outOfStock =
                (int) failures.stream().filter(r -> r == IssueFailReason.OUT_OF_STOCK).count();

        return new IssueResult(REQUESTS - failures.size(), outOfStock, unexpected);
    }

    /**
     * 캠페인 만료 시각이 실제로 지날 때까지 기다린다.
     *
     * <p>고정된 시간을 자지 않고 DB 의 expire_at 을 읽어 계산한다. 발급이 예상보다 오래 걸렸다면 이미 지났을 수도 있고, 그 경우 기다릴 필요가 없다.
     * 배치가 expire_at &lt;= NOW(3) 으로 대상을 고르므로 여유를 조금 둔다.
     */
    private void awaitCampaignExpiry() throws InterruptedException {
        Timestamp expireAt =
                jdbc.queryForObject(
                        "SELECT expire_at FROM campaigns WHERE campaign_id = ?",
                        Timestamp.class,
                        CouponIntegrationFixture.CAMPAIGN_ID);

        long waitMs =
                Duration.between(LocalDateTime.now(), expireAt.toLocalDateTime()).toMillis() + 500;

        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
    }

    private long count(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE stock_id = ? AND status = ?",
                Long.class,
                CouponIntegrationFixture.STOCK_ID,
                status);
    }

    /** 쿠폰의 만료 시각이 캠페인 만료 시각과 같은 건수 (INV-12). */
    private long couponsInheritingCampaignExpiry() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM coupons c
                  JOIN campaigns ca ON ca.campaign_id = c.campaign_id
                 WHERE c.stock_id = ?
                   AND c.expire_at = ca.expire_at
                """,
                Long.class,
                CouponIntegrationFixture.STOCK_ID);
    }

    private long expiredHistoryCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM coupon_history h
                  JOIN coupons c ON c.coupon_id = h.coupon_id
                 WHERE c.stock_id = ?
                   AND h.to_status = 'EXPIRED'
                """,
                Long.class,
                CouponIntegrationFixture.STOCK_ID);
    }
}
