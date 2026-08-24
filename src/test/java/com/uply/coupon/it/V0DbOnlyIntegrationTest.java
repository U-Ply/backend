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
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "coupon.issue.strategy=NO_LOCK",
            "coupon.save.strategy=sync-db",
            "coupon.idempotency.enabled=false"
        })
class V0DbOnlyIntegrationTest extends IntegrationTestContainers {

    private static final int BASELINE_STOCK = 10;
    private static final int BASELINE_REQUESTS = 30;
    private static final int BASELINE_TIMEOUT_SEC = 60;

    @Autowired CouponService couponService;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoundReportWriter reportWriter;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @AfterEach
    void tearDown() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture.reset();
    }

    @Test
    void V0는_Redis_campaign_키가_없어도_DB_only_경로로_발급된다() {
        fixture.createCampaign(1);
        fixture.createUsers(1, 20001L);

        couponService.issue("integration-v0-20001", request(20001L));

        assertThat(fixture.couponCount()).isEqualTo(1);
        assertThat(fixture.remaining()).isZero();

        // 발급 전에 Redis 캠페인·재고 키를 만들지 않았는데 성공했다.
        // NO_LOCK 경로가 캠페인 창과 stockId 를 MySQL 에서 읽는다는 증거다.
        assertThat(redis.keys("campaign:" + CouponIntegrationFixture.CAMPAIGN_ID + ":*"))
                .isEmpty();
        assertThat(redis.keys("stockId:" + CouponIntegrationFixture.CAMPAIGN_ID + ":*"))
                .isEmpty();
    }

    @Test
    void V0의_재고부족은_명시적으로_OUT_OF_STOCK으로_분류된다() {
        fixture.createCampaign(1);
        fixture.createUsers(2, 20001L);

        couponService.issue("integration-v0-first", request(20001L));

        assertThatIssueFails(20002L, IssueFailReason.OUT_OF_STOCK);
    }

    /**
     * V0 기준선 (test-plan 5.4).
     *
     * <p><b>이 테스트는 초과 발급을 단언하지 않는다.</b> 설계서가 그렇게 정하고 있다.
     *
     * <p>5.4 는 V0 에서 "성공 건수와 초기 재고의 <b>불일치</b>" 를 예상한다고만 적는다.
     * 초과인지 미달인지는 정하지 않는다. 실제로 이 환경에서는 미달이 나온다 —
     * coupons 의 FK 가 재고 행에 공유 잠금을 걸고 그 뒤 UPDATE 가 배타 잠금으로 올라가려다
     * 교착이 나서, 잃어버린 갱신이 커밋되기 전에 트랜잭션이 죽는다.
     * 통제 부재가 초과 발급이 아니라 가용성 붕괴로 나타나는 것이다.
     *
     * <p>그리고 5.4 는 "스레드 스케줄링에 따라 매 실행에서 동일하게 발생한다고 보장할 수 없으므로
     * 반복 실행 결과를 기록한다" 고 못 박는다. 재현을 단언하면 문서가 보장하지 않는 것을 게이트로
     * 쓰는 셈이 된다.
     *
     * <p>그래서 여기서 단언하는 것은 5.4 가 "NoLock 에서도 별도로 확인한다" 고 나열한 네 항목뿐이고,
     * 정합성 수치는 판정이 아니라 기록으로 남긴다.
     */
    @Test
    void V0_기준선_동시발급_결과를_기록한다() throws Exception {
        fixture.createCampaign(BASELINE_STOCK);
        fixture.createUsers(BASELINE_REQUESTS, 20001L);

        BurstResult result = burst();

        long issued = fixture.couponCount();
        long history = fixture.historyCount();
        long remaining = fixture.remaining();

        // ── 5.4 기록 항목: 판정하지 않고 실제 측정값을 남긴다 ──
        System.out.printf(
                "V0 baseline%n"
                        + "  stock=%d requests=%d%n"
                        + "  issued=%d (delta=%+d) remaining=%d%n"
                        + "  outOfStock=%d dbConflict=%d%n"
                        + "  remaining == total - issued ? %s%n",
                BASELINE_STOCK,
                BASELINE_REQUESTS,
                issued,
                issued - BASELINE_STOCK,
                remaining,
                result.outOfStock(),
                result.dbConflicts(),
                remaining == BASELINE_STOCK - issued);

        // ── 5.4 별도 확인 항목: 여기는 단언한다 ──

        // (1) 전체 요청이 제한 시간 안에 종료되는가 — burst() 안에서 확인한다.

        // (2) 처리되지 않은 예외 — burst() 안에서 확인한다.
        //     DB 오류(교착)는 6.6 에 따라 V0 에서 정상이므로 위에 수치로만 남긴다.

        // (3) 캠페인별 1인 1매 UNIQUE 제약이 동작하는가
        assertThat(duplicateUserCount())
                .as("같은 캠페인에서 한 사용자가 두 장 이상 받았다")
                .isZero();

        // (4) 쿠폰과 이력 저장 결과가 일치하는가
        assertThat(history)
                .as("쿠폰 수와 이력 수가 어긋났다")
                .isEqualTo(issued);

        // 빈 회차는 아무것도 재현하지 못한 것이다.
        // 이건 스케줄링과 무관하게 성립해야 한다.
        assertThat(issued)
                .as("한 장도 발급되지 않았다. 부하가 걸리지 않았다는 뜻이다")
                .isPositive();

        // 리포트는 남긴다.
        // V0 의 위반 수는 판정이 아니라 §11 비교표의 기록 항목이다.
        RoundReportAssert.assertBaselineRecorded(reportWriter.writeReport("V0"));
    }

    // ------------------------------------------------------------------

    /**
     * 한 번의 동시 발급 결과.
     *
     * @param outOfStock 재고 소진으로 정상 거절된 수
     * @param dbConflicts DB 교착·락 획득 실패로 죽은 수.
     *     test-plan 6.6 의 CONCURRENCY_CONFLICT 에 해당한다
     */
    private record BurstResult(int outOfStock, int dbConflicts) {}

    /**
     * BASELINE_REQUESTS 건을 같은 순간에 출발시킨다.
     *
     * <p>래치가 없으면 요청이 흩어져 경합 자체가 약해질 수 있으므로,
     * 모든 작업이 준비된 뒤 동시에 시작한다.
     */
    private BurstResult burst() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BASELINE_REQUESTS);

        CountDownLatch ready = new CountDownLatch(BASELINE_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(BASELINE_REQUESTS);

        ConcurrentLinkedQueue<IssueFailReason> issueFailures =
                new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<DataAccessException> dbConflicts =
                new ConcurrentLinkedQueue<>();

        ConcurrentLinkedQueue<Throwable> unexpected =
                new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < BASELINE_REQUESTS; i++) {
                long userId = 20001L + i;

                pool.submit(
                        () -> {
                            ready.countDown();

                            try {
                                assertThat(start.await(10, TimeUnit.SECONDS))
                                        .as("동시 발급 시작 신호를 10초 안에 받지 못했습니다.")
                                        .isTrue();

                                couponService.issue(
                                        "integration-v0-base-" + userId,
                                        request(userId));

                            } catch (CouponIssueException e) {
                                issueFailures.add(e.getReason());

                            } catch (DataAccessException e) {
                                // 교착(Deadlock)·락 획득 실패.
                                //
                                // 같은 재고 행에 트랜잭션이 몰리면 InnoDB 가 교착을 감지하고
                                // 일부를 죽일 수 있다.
                                //
                                // test-plan 6.6:
                                // "CONCURRENCY_CONFLICT 는 V0 에서 발생하는 것이 정상이다.
                                // 실패로 처리하지 않고 수치로 기록한다."
                                //
                                // HTTP 경로에서는 컨트롤러가 이것을
                                // 503 CONCURRENCY_CONFLICT 로 매핑한다.
                                // 여기서는 서비스를 직접 부르므로 매핑 전 예외가 올라온다.
                                dbConflicts.add(e);

                            } catch (Throwable t) {
                                unexpected.add(t);

                            } finally {
                                done.countDown();
                            }
                        });
            }

            // 모든 발급 작업이 준비될 때까지 무한 대기하지 않는다.
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("모든 발급 작업이 10초 안에 준비되지 않았습니다.")
                    .isTrue();

            // 모든 worker가 준비된 뒤 동시에 발급을 시작한다.
            start.countDown();

            // 전체 요청도 제한 시간 안에 종료되어야 한다.
            assertThat(done.await(BASELINE_TIMEOUT_SEC, TimeUnit.SECONDS))
                    .as(
                            "%d 초 안에 %d 건이 종료되지 않았다",
                            BASELINE_TIMEOUT_SEC,
                            BASELINE_REQUESTS)
                    .isTrue();

            // 도메인 거절이나 DB 동시성 오류가 아닌 예상 밖의 예외만 실패로 처리한다.
            assertThat(unexpected)
                    .as("V0에서 예상 범위 밖의 예외")
                    .isEmpty();

            int outOfStock =
                    (int)
                            issueFailures.stream()
                                    .filter(r -> r == IssueFailReason.OUT_OF_STOCK)
                                    .count();

            return new BurstResult(outOfStock, dbConflicts.size());

        } finally {
            // 테스트 성공/실패와 관계없이 executor를 반드시 종료한다.
            pool.shutdownNow();
        }
    }

    /** 같은 캠페인에서 두 장 이상 받은 사용자 수. UNIQUE 제약이 살아 있으면 0 이다. */
    private long duplicateUserCount() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM (
                        SELECT user_id
                          FROM coupons
                         WHERE campaign_id = ?
                         GROUP BY user_id
                        HAVING COUNT(*) > 1
                       ) d
                """,
                Long.class,
                CouponIntegrationFixture.CAMPAIGN_ID);
    }

    private void assertThatIssueFails(long userId, IssueFailReason expected) {
        try {
            couponService.issue("integration-v0-" + userId, request(userId));
        } catch (CouponIssueException e) {
            assertThat(e.getReason()).isEqualTo(expected);
            return;
        }

        throw new AssertionError("발급이 성공하면 안 된다: userId=" + userId);
    }

    private CouponIssueRequest request(long userId) {
        return new CouponIssueRequest(
                userId,
                CouponIntegrationFixture.CAMPAIGN_ID,
                CouponIntegrationFixture.ROUTE,
                CouponIntegrationFixture.FARE);
    }
}