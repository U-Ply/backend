package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NoLockIssueStrategyTest {

    @Autowired NoLockIssueStrategy strategy;

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

    @Test
    @DisplayName("락이 없으면 재고보다 많은 쿠폰이 발급된다 (동시성 제어 부재 증명)")
    void 락이_없으면_초과발급이_발생한다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(USER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(USER_COUNT);

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
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        int issued = couponCount(); // 실제 발급된 쿠폰 수
        int consumed = TOTAL_STOCK - remainingStock(); // 재고가 줄어든 양

        System.out.printf(
                "발급 %d건 / 재고 차감 %d건 → 유실 %d건 (락 경합 실패 %d건, 기타 예외 %d건)%n",
                issued, consumed, issued - consumed, deadlock.get(), error.get());

        // 락이 없으므로 차감이 유실되어, 발급 수가 재고 차감량보다 많아진다
        assertThat(issued).isGreaterThan(consumed);
    }
}
