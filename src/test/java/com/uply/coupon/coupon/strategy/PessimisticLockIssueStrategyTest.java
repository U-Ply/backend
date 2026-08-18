package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.uply.coupon.common.exception.CampaignNotFoundException;
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
class PessimisticLockIssueStrategyTest {

    @Autowired PessimisticLockIssueStrategy strategy;

    @SpyBean CouponHistoryRepository couponHistoryRepository;

    @Autowired JdbcTemplate jdbcTemplate;

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
    @DisplayName("campaignId와 stockId 조합이 어긋나면 CAMPAIGN_NOT_FOUND로 거부된다")
    void 캠페인_재고풀_조합_불일치() {
        // stockId 는 실재하지만 campaignId 가 다르다. findCouponExpireAt 이 두 조건을
        // 함께 걸기 때문에 락을 잡기 전 단계에서 걸러진다.
        assertThatThrownBy(() -> strategy.issue(999L, 1L, STOCK_ID, "mismatch-key"))
                .isInstanceOf(CampaignNotFoundException.class);

        // 검증 단계에서 막혔으므로 재고와 쿠폰에 아무 영향이 없어야 한다.
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(couponCount()).isZero();
    }

    @Test
    @DisplayName("이력 저장이 실패하면 재고 차감과 쿠폰 생성이 모두 롤백된다")
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
    @DisplayName("재고 10개에 30명이 동시 요청해도 정확히 10개만 발급된다")
    void 동시요청시_초과발급이_발생하지_않는다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(USER_COUNT);
        CountDownLatch start = new CountDownLatch(1); // 출발 신호
        CountDownLatch done = new CountDownLatch(USER_COUNT);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger alreadyIssued = new AtomicInteger();
        AtomicInteger lockTimeout = new AtomicInteger();
        AtomicInteger error = new AtomicInteger(); // 처리 안 된 예외 = 실제 서비스의 500

        for (int i = 1; i <= USER_COUNT; i++) {
            long userId = i;
            executor.submit(
                    () -> {
                        try {
                            start.await(); // 모든 스레드를 한 지점에 모았다가 동시에 출발시킨다
                            IssueResult result =
                                    strategy.issue(CAMPAIGN_ID, userId, STOCK_ID, "key-" + userId);
                            if (result.success()) {
                                success.incrementAndGet();
                            } else if (result.reason() == IssueFailReason.OUT_OF_STOCK) {
                                outOfStock.incrementAndGet();
                            } else if (result.reason() == IssueFailReason.ALREADY_ISSUED) {
                                alreadyIssued.incrementAndGet();
                            } else if (result.reason() == IssueFailReason.LOCK_TIMEOUT) {
                                lockTimeout.incrementAndGet();
                            }
                        } catch (Exception e) {
                            error.incrementAndGet();
                            e.printStackTrace();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf(
                "성공 %d건 / 재고소진 %d건 / 중복 %d건 / 락 timeout %d건 / 기타 예외 %d건" + " (쿠폰 %d장, 잔여 재고 %d장)%n",
                success.get(),
                outOfStock.get(),
                alreadyIssued.get(),
                lockTimeout.get(),
                error.get(),
                couponCount(),
                remainingStock());

        assertThat(finished).isTrue(); // 제한 시간 안에 모든 요청이 끝나야 한다

        // 요청이 어디로도 새지 않았는지 확인 (합이 안 맞으면 집계되지 않은 결과가 있다는 뜻)
        assertThat(
                        success.get()
                                + outOfStock.get()
                                + alreadyIssued.get()
                                + lockTimeout.get()
                                + error.get())
                .isEqualTo(USER_COUNT);

        assertThat(error.get()).isZero(); // 부하테스트의 "5xx 0건"에 대응
        assertThat(success.get()).isEqualTo(TOTAL_STOCK); // 정확히 재고만큼만 성공
        assertThat(couponCount()).isEqualTo(TOTAL_STOCK); // DB에 실제 저장된 쿠폰 수도 일치
        assertThat(remainingStock()).isZero(); // 재고 정확히 소진
    }
}
