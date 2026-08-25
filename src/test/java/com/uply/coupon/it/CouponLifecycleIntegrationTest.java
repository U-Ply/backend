package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.coupon.service.CouponStateTransitionService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"coupon.idempotency.enabled=false"})
class CouponLifecycleIntegrationTest extends IntegrationTestContainers {

    private static final int ISSUED_COUNT = 15;

    @Autowired JdbcTemplate jdbc;
    @Autowired CouponIntegrationFixture fixture;
    @Autowired CouponStateTransitionService transitionService;

    @BeforeEach
    void setUp() {
        fixture.reset();
        fixture.createCampaign(ISSUED_COUNT);
        fixture.createUsers(ISSUED_COUNT, 50001L);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expire = now.plusHours(1);

        for (int i = 0; i < ISSUED_COUNT; i++) {
            long couponId = 50001L + i;
            long userId = 50001L + i;

            jdbc.update(
                    """
                    INSERT INTO coupons
                        (coupon_id, user_id, campaign_id, stock_id, status,
                         issued_at, expire_at, created_at)
                    VALUES (?, ?, ?, ?, 'ISSUED', ?, ?, ?)
                    """,
                    couponId,
                    userId,
                    CouponIntegrationFixture.CAMPAIGN_ID,
                    CouponIntegrationFixture.STOCK_ID,
                    now,
                    expire,
                    now);

            jdbc.update(
                    """
                    INSERT INTO coupon_history
                        (coupon_id, from_status, to_status, idempotency_key, event_at)
                    VALUES (?, NULL, 'ISSUED', ?, ?)
                    """,
                    couponId,
                    "integration-issued-" + couponId,
                    now);
        }

        // 쿠폰을 발급 API 가 아니라 JDBC 로 직접 넣었으므로 재고가 줄지 않았다.
        // 이 테스트가 보려는 것은 "사용·취소가 재고를 되돌리지 않는다" 이고, 그건 발급 시점에
        // 재고가 이미 소진돼 있어야 성립한다. 발급 상태를 손으로 만든다면 그 부수효과도
        // 손으로 만들어야 한다.
        fixture.setRemaining(0);
    }

    @AfterEach
    void tearDown() {
        fixture.reset();
    }

    @Test
    void 사용_10건_취소_5건은_재고를_복구하지_않는다() {
        assertThat(fixture.remaining()).as("전제: 발급으로 재고가 소진된 상태").isZero();

        for (int i = 0; i < 10; i++) {
            long couponId = 50001L + i;
            transitionService.use(couponId, "integration-use-" + couponId);
        }

        for (int i = 0; i < 5; i++) {
            long couponId = 50001L + i;
            transitionService.cancel(couponId, "integration-cancel-" + couponId);
        }

        assertThat(count("ISSUED")).isEqualTo(5);
        assertThat(count("USED")).isEqualTo(5);
        assertThat(count("CANCELLED")).isEqualTo(5);

        // test-plan 2.8 — 발급된 재고는 상태와 무관하게 영구 소진한다.
        assertThat(fixture.remaining()).as("사용·취소가 재고를 되돌리면 안 된다").isZero();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM coupon_history WHERE to_status IN ('USED','CANCELLED')",
                                Long.class))
                .isEqualTo(15L);
    }

    private long count(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE stock_id = ? AND status = ?",
                Long.class,
                CouponIntegrationFixture.STOCK_ID,
                status);
    }
}
