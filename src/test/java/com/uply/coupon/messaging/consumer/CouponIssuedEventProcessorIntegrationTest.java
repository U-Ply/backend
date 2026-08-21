package com.uply.coupon.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.coupon.repository.CouponIssuanceProgressRepository;
import com.uply.coupon.messaging.event.CouponIssuedEvent;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CouponIssuedEventProcessorIntegrationTest {

    private static final long CAMPAIGN_ID = 1L;
    private static final long STOCK_ID = 1L;
    private static final int TOTAL_STOCK = 10;

    @Autowired private CouponIssuedEventProcessor eventProcessor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private CouponIssuanceProgressRepository progressRepository;

    // 각 테스트 전에 기존 데이터를 삭제하고 사용자/캠페인/재고 시드 생성
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM coupon_history");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM campaign_stocks");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");

        jdbcTemplate.update(
                "INSERT INTO users (user_id, email, name) VALUES (1, 'user1@test.com', 'user1')");
        jdbcTemplate.update(
                "INSERT INTO users (user_id, email, name) VALUES (2, 'user2@test.com', 'user2')");
        jdbcTemplate.update(
                "INSERT INTO campaigns (campaign_id, name, open_at, expire_at) "
                        + "VALUES (?, '제주 얼리버드 특가', '2026-08-01 00:00:00.000', "
                        + "'2026-09-01 00:00:00.000')",
                CAMPAIGN_ID);
        jdbcTemplate.update(
                "INSERT INTO campaign_stocks "
                        + "(stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock) "
                        + "VALUES (?, ?, 'JEJU', 'ECONOMY', ?, ?)",
                STOCK_ID,
                CAMPAIGN_ID,
                TOTAL_STOCK,
                TOTAL_STOCK);
    }

    // 동일 이벤트를 두 번 처리해도 쿠폰/이력/재고가 한 번만 반영되는지 확인
    @Test
    void eventIsPersistedExactlyOnce() {
        CouponIssuedEvent event = event(1001L, 1L, "550e8400-e29b-41d4-a716-446655440000");

        assertThat(eventProcessor.process(event)).isTrue();
        assertThat(eventProcessor.process(event)).isFalse();

        assertThat(count("coupons")).isEqualTo(1);
        assertThat(count("coupon_history")).isEqualTo(1);
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM coupons WHERE coupon_id = ?",
                                String.class,
                                event.couponId()))
                .isEqualTo("ISSUED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM coupon_history "
                                        + "WHERE coupon_id = ? AND from_status IS NULL "
                                        + "AND to_status = 'ISSUED'",
                                Integer.class,
                                event.couponId()))
                .isEqualTo(1);
    }

    // 같은 사용자의 동일 캠페인 중복 발급을 추가 저장 없이 차단하는지 확인
    @Test
    void sameCampaignAndUserWithDifferentCouponIdIsSkipped() {
        CouponIssuedEvent first = event(1001L, 1L, "550e8400-e29b-41d4-a716-446655440001");
        CouponIssuedEvent duplicate = event(1002L, 1L, "550e8400-e29b-41d4-a716-446655440002");

        assertThat(eventProcessor.process(first)).isTrue();
        assertThat(eventProcessor.process(duplicate)).isFalse();

        assertThat(count("coupons")).isEqualTo(1);
        assertThat(count("coupon_history")).isEqualTo(1);
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1);
    }

    // 재고 감소가 실패하면 쿠폰과 발급 이력이 모두 롤백되는지 확인
    @Test
    void stockDecreaseFailureRollsBackCouponAndHistory() {
        jdbcTemplate.update(
                "UPDATE campaign_stocks SET remaining_stock = 0 WHERE stock_id = ?", STOCK_ID);
        CouponIssuedEvent event = event(1001L, 1L, "550e8400-e29b-41d4-a716-446655440003");

        assertThatThrownBy(() -> eventProcessor.process(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("재고 차감");

        assertThat(count("coupons")).isZero();
        assertThat(count("coupon_history")).isZero();
        assertThat(remainingStock()).isZero();
    }

    private CouponIssuedEvent event(Long couponId, Long userId, String idempotencyKey) {
        return new CouponIssuedEvent(
                couponId,
                userId,
                CAMPAIGN_ID,
                STOCK_ID,
                idempotencyKey,
                Instant.parse("2026-08-15T01:00:00Z"),
                Instant.parse("2026-08-15T01:00:00.050Z"));
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private int remainingStock() {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM campaign_stocks WHERE stock_id = ?",
                Integer.class,
                STOCK_ID);
    }
}
