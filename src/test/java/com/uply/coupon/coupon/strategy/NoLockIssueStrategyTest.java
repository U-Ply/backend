package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.uply.coupon.coupon.domain.CouponHistory;
import com.uply.coupon.coupon.repository.CouponHistoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NoLockIssueStrategyTest {

    @Autowired NoLockIssueStrategy strategy;

    @Autowired JdbcTemplate jdbcTemplate;

    @SpyBean CouponHistoryRepository couponHistoryRepository;

    private static final long CAMPAIGN_ID = 1L;
    private static final long STOCK_ID = 1L;
    private static final int TOTAL_STOCK = 10;
    private static final int USER_COUNT = 30;

    @BeforeEach
    void setUp() {
        // FK 역순으로 정리 (테스트끼리 데이터가 섞이지 않도록)
        jdbcTemplate.update("DELETE FROM coupon_history");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM campaign_stocks");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");

        for (int i = 1; i <= USER_COUNT; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)",
                    i,
                    "user" + i + "@test.com",
                    "유저" + i);
        }
        jdbcTemplate.update(
                "INSERT INTO campaigns (campaign_id, name, open_at, expire_at) "
                        + "VALUES (?, ?, NOW(3), DATE_ADD(NOW(3), INTERVAL 30 DAY))",
                CAMPAIGN_ID,
                "제주 얼리버드 특가");
        jdbcTemplate.update(
                "INSERT INTO campaign_stocks "
                        + "(stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock) "
                        + "VALUES (?, ?, 'JEJU', 'ECONOMY', ?, ?)",
                STOCK_ID,
                CAMPAIGN_ID,
                TOTAL_STOCK,
                TOTAL_STOCK);
    }

    private int remainingStock() {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM campaign_stocks WHERE stock_id = ?",
                Integer.class,
                STOCK_ID);
    }

    private int couponCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE stock_id = ?", Integer.class, STOCK_ID);
    }

    private int historyCount() {
        // coupon_history 에는 stock_id 가 없으므로 전체를 센다
        // @BeforeEach 가 매번 테이블을 비우므로 다른 테스트의 데이터가 섞이지 않음
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history", Integer.class);
    }

    @Test
    @DisplayName("정상 발급되면 쿠폰이 생성되고 재고가 1 줄어든다")
    void 정상_발급() {
        IssueResult result = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "key-1");

        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isNotNull();
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1);
    }

    @Test
    @DisplayName("재고가 모두 소진되면 OUT_OF_STOCK을 반환하고 재고는 0 아래로 내려가지 않는다")
    void 재고_소진() {
        // 유저 1~10이 재고를 전부 가져 감
        for (int i = 1; i <= TOTAL_STOCK; i++) {
            strategy.issue(CAMPAIGN_ID, (long) i, STOCK_ID, "key-" + i);
        }

        // 11번째 유저는 실패해야 함
        IssueResult result = strategy.issue(CAMPAIGN_ID, 11L, STOCK_ID, "key-11");

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.OUT_OF_STOCK);
        assertThat(remainingStock()).isZero();
        assertThat(couponCount()).isEqualTo(TOTAL_STOCK);
    }

    @Test
    @DisplayName("같은 유저가 다른 키로 다시 요청하면 ALREADY_ISSUED이고 재고는 1만 줄어든다")
    void 중복_발급_차단() {
        strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "key-a");

        IssueResult result = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "key-b");

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.ALREADY_ISSUED);
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1); // 재고가 추가로 깎이면 안 된다
        assertThat(couponCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 idempotencyKey로 재시도하면 같은 쿠폰을 돌려주고 재고를 두 번 깎지 않는다")
    void 멱등성_보장() {
        IssueResult first = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "same-key");
        IssueResult retry = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "same-key");

        assertThat(first.success()).isTrue();
        assertThat(retry.success()).isTrue();
        assertThat(retry.couponId()).isEqualTo(first.couponId()); // 같은 쿠폰이어야 한다
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1); // 재고는 1만 차감
        assertThat(couponCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("쿠폰 만료 시각은 캠페인의 expire_at을 그대로 따른다")
    void 만료시각은_캠페인_기준() {
        IssueResult result = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "expire-key");

        assertThat(result.success()).isTrue();

        // @BeforeEach 가 캠페인을 NOW(3) + 30일로 만들어 두었다.
        // 하드코딩된 "발급일 + 7일" 이 아니라 이 값이 그대로 쿠폰에 실려야 한다.
        LocalDateTime campaignExpireAt =
                jdbcTemplate.queryForObject(
                        "SELECT expire_at FROM campaigns WHERE campaign_id = ?",
                        LocalDateTime.class,
                        CAMPAIGN_ID);
        LocalDateTime couponExpireAt =
                jdbcTemplate.queryForObject(
                        "SELECT expire_at FROM coupons WHERE coupon_id = ?",
                        LocalDateTime.class,
                        result.couponId());

        assertThat(couponExpireAt).isEqualTo(campaignExpireAt);
    }

    @Test
    @DisplayName("발급 시각은 JVM이 아니라 DB의 NOW(3)를 따른다")
    void 발급시각은_DB_기준() {
        IssueResult result = strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "issued-at-key");

        assertThat(result.success()).isTrue();

        LocalDateTime issuedAt =
                jdbcTemplate.queryForObject(
                        "SELECT issued_at FROM coupons WHERE coupon_id = ?",
                        LocalDateTime.class,
                        result.couponId());
        LocalDateTime databaseTime =
                jdbcTemplate.queryForObject("SELECT NOW(3)", LocalDateTime.class);

        // JVM은 UTC, MySQL 서버는 KST로 돌기 때문에 JVM 시각을 쓰면 9시간이 어긋난다.
        // DB 시각을 썼다면 방금 발급했으므로 차이가 몇 초 이내여야 한다.
        assertThat(Duration.between(issuedAt, databaseTime).abs())
                .isLessThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("락이 없어도 한 요청 안에서는 원자적이라 이력 저장 실패 시 전부 롤백된다")
    void 트랜잭션_롤백() {
        doThrow(new DataIntegrityViolationException("테스트용 강제 예외"))
                .when(couponHistoryRepository)
                .save(any(CouponHistory.class));

        assertThatThrownBy(() -> strategy.issue(CAMPAIGN_ID, 1L, STOCK_ID, "rollback-key"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK); // 차감이 되돌려졌는지 확인
        assertThat(couponCount()).isZero(); // 쿠폰이 남지 않았는지 확인
        assertThat(historyCount()).isZero(); // 이력이 남지 않았는지 확인
    }

    @Test
    @DisplayName("락이 없으면 재고 차감이 유실되어 쿠폰 수와 재고가 어긋난다")
    void 락이_없으면_재고차감이_유실된다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(USER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(USER_COUNT);

        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger alreadyIssued = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger deadlock = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        for (int i = 1; i <= USER_COUNT; i++) {
            long userId = i;
            executor.submit(
                    () -> {
                        try {
                            start.await(); // 모든 스레드를 모았다가 동시에 출발시킨다
                            IssueResult result =
                                    strategy.issue(
                                            CAMPAIGN_ID, userId, STOCK_ID, "nolock-" + userId);
                            if (result.success()) {
                                success.incrementAndGet();
                            } else if (result.reason() == IssueFailReason.OUT_OF_STOCK) {
                                outOfStock.incrementAndGet();
                            } else if (result.reason() == IssueFailReason.ALREADY_ISSUED) {
                                alreadyIssued.incrementAndGet();
                            }
                        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                            deadlock.incrementAndGet();
                        } catch (Exception e) {
                            error.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue(); // 제한 시간 안에 모든 요청이 끝나야 한다

        int issued = couponCount(); // 실제 발급된 쿠폰 수
        int consumed = TOTAL_STOCK - remainingStock(); // 재고가 줄어든 양
        int overIssued = Math.max(0, issued - TOTAL_STOCK); // 초기 재고를 넘겨 발급된 수

        System.out.printf(
                "성공 응답 %d건 / 실제 쿠폰 %d건 / 재고 차감 %d건 → 유실 %d건, 초과 발급 %d건"
                        + " (재고소진 %d건, 중복 %d건, 락 경합 실패 %d건, 기타 예외 %d건)%n",
                success.get(),
                issued,
                consumed,
                issued - consumed,
                overIssued,
                outOfStock.get(),
                alreadyIssued.get(),
                deadlock.get(),
                error.get());

        // 요청이 어디로도 새지 않았는지 확인 (합이 안 맞으면 집계되지 않은 결과가 있다는 뜻)
        assertThat(
                        success.get()
                                + outOfStock.get()
                                + alreadyIssued.get()
                                + deadlock.get()
                                + error.get())
                .isEqualTo(USER_COUNT);

        // 락이 없으므로 차감이 유실되어, 발급 수가 재고 차감량보다 많아진다
        assertThat(issued).isGreaterThan(consumed);
    }
}
