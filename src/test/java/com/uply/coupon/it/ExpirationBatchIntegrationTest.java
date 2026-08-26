package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
 * <p><b>expire_at 을 임의의 과거로 옮기지도 않는다.</b> 그렇게 하면 {@code issued_at >= expire_at} 인 쿠폰이 생기고, 그건
 * INV-06(시각 순서)·INV-11(캠페인 기간 내 발급)이 위반으로 잡아야 할 데이터를 검증하는 쪽에서 만들어내는 꼴이 된다.
 *
 * <p><b>그렇다고 시간이 흐르기를 기다리지도 않는다.</b> 예전에는 캠페인 창을 5 초로 좁히고 실제로 잤다. 그 5 초가 캠페인 창·발급 마감·만료 시점 세 역할을
 * 겸했고, 스레드 {@value #REQUESTS} 개가 커넥션 풀을 나눠 쓰며 비관적 락을 잡는 구간이 5 초를 넘기면 늦은 요청이 CAMPAIGN_EXPIRED 로 실패해
 * 집계가 어긋났다(3 회 중 1 회 관측). 대신 발급이 끝난 뒤 캠페인을 <b>마지막 issued_at 직후</b>로 닫는다. 이미 지나간 시각이므로 기다릴 필요가 없고,
 * INV-11 과 INV-12 는 그대로 성립한다.
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

    /**
     * 발급이 끝나기를 기다리는 상한. 캠페인 창과 무관하다.
     *
     * <p>이 값은 판정 기준이 아니라 교착·커넥션 고갈 감지용이다. 정상 회차에서는 1 초 안에 끝난다. 예전 구조에서 이 값이 캠페인 창을 겸하면서 간헐 실패의 원인이
     * 됐다.
     */
    private static final int ISSUE_TIMEOUT_SEC = 30;

    /** 애플리케이션 시계와 MySQL 시계가 어긋난 만큼만 기다린다. 보통 첫 폴링에서 통과한다. */
    private static final Duration EXPIRY_PREDICATE_TIMEOUT = Duration.ofSeconds(10);

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

        // 오픈 시각만 확실히 과거로 당긴다. createCampaign 은 2 시간짜리를 만들고, 만료는 발급이
        // 끝난 뒤 closeCampaignJustAfterLastIssue() 가 닫는다. 창을 미리 좁히면 "발급이 그 안에
        // 끝나야 한다" 는 시간 제약이 생긴다.
        jdbc.update(
                "UPDATE campaigns SET open_at = ? WHERE campaign_id = ?",
                LocalDateTime.now().minusSeconds(5),
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

        // ── 2. 캠페인을 마지막 발급 직후로 닫는다 (시간을 기다리지 않는다) ──
        closeCampaignJustAfterLastIssue();
        awaitExpirationPredicate();

        // ── 3. 만료 배치 ──
        var execution =
                jobLauncher.launchJob(
                        new JobParametersBuilder()
                                .addLong("integrationRun", System.nanoTime())
                                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // COMPLETED 만 보면 안 된다. 리더가 0 건을 읽어도 배치는 COMPLETED 로 끝난다.
        assertThat(readCount(execution)).as("만료 배치가 읽은 행").isEqualTo(STOCK);

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
        boolean finished = done.await(ISSUE_TIMEOUT_SEC, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished)
                .as("발급 %d 건이 %d 초 안에 끝나지 않았다. 교착이나 커넥션 고갈을 의심한다", REQUESTS, ISSUE_TIMEOUT_SEC)
                .isTrue();

        int outOfStock =
                (int) failures.stream().filter(r -> r == IssueFailReason.OUT_OF_STOCK).count();
        return new IssueResult(REQUESTS - failures.size(), outOfStock, unexpected);
    }

    /**
     * 캠페인을 "마지막 발급 직후" 로 닫는다. 시간이 흐르기를 기다리지 않는다.
     *
     * <p>expire_at 을 임의의 과거로 옮기면 {@code issued_at >= expire_at} 인 쿠폰이 생긴다. INV-11 이 위반으로 잡아야 할 데이터를
     * 검증하는 쪽에서 만들어내는 꼴이므로, 마지막 issued_at 보다 1ms 뒤로만 옮긴다. 발급이 이미 끝난 뒤라 그 시각은 이미 과거이고, 기다릴 필요가 없다.
     *
     * <p>쿠폰의 expire_at 도 같이 옮긴다. 만료 배치가 보는 것은 {@code coupons.expire_at} 이고, 한쪽만 바꾸면 INV-12(만료 시각
     * 캠페인 상속)가 깨진다.
     */
    private void closeCampaignJustAfterLastIssue() {
        Timestamp lastIssuedAt =
                jdbc.queryForObject(
                        "SELECT MAX(issued_at) FROM coupons WHERE stock_id = ?",
                        Timestamp.class,
                        CouponIntegrationFixture.STOCK_ID);
        LocalDateTime closeAt = lastIssuedAt.toLocalDateTime().plusNanos(1_000_000L);

        jdbc.update(
                "UPDATE campaigns SET expire_at = ? WHERE campaign_id = ?",
                closeAt,
                CouponIntegrationFixture.CAMPAIGN_ID);
        jdbc.update(
                "UPDATE coupons SET expire_at = ? WHERE stock_id = ?",
                closeAt,
                CouponIntegrationFixture.STOCK_ID);

        // 픽스처가 불변식을 깨는 데이터를 만들지 않았는지 여기서 단언한다.
        // 나중에 누가 이 창을 다시 만지면 만료 배치보다 이 줄이 먼저 깨진다.
        assertThat(couponsInheritingCampaignExpiry())
                .as("INV-12 — 쿠폰이 캠페인 만료 시각을 상속")
                .isEqualTo(STOCK);
        assertThat(couponsIssuedOnOrAfterExpiry())
                .as("INV-11 — issued_at >= expire_at 인 쿠폰을 만들면 안 된다")
                .isZero();
    }

    /**
     * 만료 배치가 실제로 쓰는 조건이 성립할 때까지 기다린다.
     *
     * <p>issued_at 은 애플리케이션 시계로, 배치의 cutoff 는 MySQL 의 {@code NOW(3)} 으로 찍힌다. 두 시계가 밀리초 단위로 어긋날 수
     * 있으므로 고정 시간이 아니라 조건을 기다린다. 어긋나지 않았다면 첫 폴링에서 통과한다.
     */
    private void awaitExpirationPredicate() {
        await().atMost(EXPIRY_PREDICATE_TIMEOUT)
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(
                        () ->
                                assertThat(expirationTargetCount())
                                        .as("expire_at <= NOW(3) 인 ISSUED 쿠폰")
                                        .isEqualTo(STOCK));
    }

    /** 만료 배치의 리더가 고르는 것과 같은 조건. */
    private long expirationTargetCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM coupons
                 WHERE stock_id = ?
                   AND status = 'ISSUED'
                   AND expire_at <= NOW(3)
                """,
                Long.class,
                CouponIntegrationFixture.STOCK_ID);
    }

    /** INV-11 은 expire_at 정각 발급도 위반으로 본다 (정책 C-2). */
    private long couponsIssuedOnOrAfterExpiry() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM coupons c
                  JOIN campaigns g ON g.campaign_id = c.campaign_id
                 WHERE c.stock_id = ?
                   AND c.issued_at >= g.expire_at
                """,
                Long.class,
                CouponIntegrationFixture.STOCK_ID);
    }

    /** expirationStep 이 실제로 읽은 행 수. Tasklet 이 아니라 Chunk 라 이 값이 의미를 갖는다. */
    private long readCount(org.springframework.batch.core.JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(step -> "expirationStep".equals(step.getStepName()))
                .mapToLong(org.springframework.batch.core.StepExecution::getReadCount)
                .sum();
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
