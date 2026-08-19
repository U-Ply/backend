package com.uply.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CampaignNotOpenException;
import com.uply.coupon.coupon.dto.request.CouponIssueRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
        properties = {"coupon.issue.strategy=PESSIMISTIC_LOCK", "coupon.idempotency.enabled=false"})
class CouponIssueValidationIntegrationTest {

    @Autowired CouponService couponService;

    @Autowired JdbcTemplate jdbcTemplate;

    private static final long CAMPAIGN_ID = 1L;
    private static final long STOCK_ID = 1L;
    private static final int TOTAL_STOCK = 10;
    private static final String ROUTE_ID = "JEJU";
    private static final String FARE_CLASS = "ECONOMY";

    @BeforeEach
    void setUp() {
        // FK 역순으로 정리 (테스트끼리 데이터가 섞이지 않도록)
        jdbcTemplate.update("DELETE FROM coupon_history");
        jdbcTemplate.update("DELETE FROM coupons");
        jdbcTemplate.update("DELETE FROM campaign_stocks");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");

        jdbcTemplate.update(
                "INSERT INTO users (user_id, email, name) VALUES (?, ?, ?)",
                1L,
                "user1@test.com",
                "유저1");
    }

    /** 캠페인과 재고 풀을 만든다. openAtOffsetDays 가 양수면 아직 오픈 전인 캠페인이 된다. */
    private void insertCampaign(int openAtOffsetDays) {
        jdbcTemplate.update(
                "INSERT INTO campaigns (campaign_id, name, open_at, expire_at) "
                        + "VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL ? DAY), "
                        + "DATE_ADD(NOW(3), INTERVAL 30 DAY))",
                CAMPAIGN_ID,
                "제주 얼리버드 특가",
                openAtOffsetDays);
        jdbcTemplate.update(
                "INSERT INTO campaign_stocks "
                        + "(stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                STOCK_ID,
                CAMPAIGN_ID,
                ROUTE_ID,
                FARE_CLASS,
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
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupons", Integer.class);
    }

    @Test
    @DisplayName("오픈 시각 이전 요청은 CAMPAIGN_NOT_OPEN으로 거부된다")
    void 오픈_전_발급_거부() {
        insertCampaign(1); // 하루 뒤에 열리는 캠페인

        CouponIssueRequest request = new CouponIssueRequest(1L, CAMPAIGN_ID, ROUTE_ID, FARE_CLASS);

        assertThatThrownBy(() -> couponService.issue("not-open-key", request))
                .isInstanceOf(CampaignNotOpenException.class);

        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(couponCount()).isZero();
    }

    @Test
    @DisplayName("오픈된 캠페인은 정상 발급된다")
    void 오픈_후_발급_성공() {
        insertCampaign(0); // 지금 열린 캠페인

        CouponIssueRequest request = new CouponIssueRequest(1L, CAMPAIGN_ID, ROUTE_ID, FARE_CLASS);

        assertThat(couponService.issue("opened-key", request).couponId()).isNotNull();
        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK - 1);
        assertThat(couponCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("존재하지 않는 노선·좌석 등급 조합은 CAMPAIGN_NOT_FOUND로 거부된다")
    void 재고풀_없음() {
        insertCampaign(0);

        CouponIssueRequest request = new CouponIssueRequest(1L, CAMPAIGN_ID, "FUKUOKA", FARE_CLASS);

        assertThatThrownBy(() -> couponService.issue("no-stock-key", request))
                .isInstanceOf(CampaignNotFoundException.class);

        assertThat(remainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(couponCount()).isZero();
    }
}
