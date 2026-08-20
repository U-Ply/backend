package com.uply.coupon.operation.verification;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@TestComponent
public class InvariantFixture {

    public static final long CAMPAIGN_OPEN = 1L; // 진행 중
    public static final long CAMPAIGN_CLOSED = 2L; // 종료
    public static final long STOCK_OPEN = 1L;
    public static final long STOCK_CLOSED = 2L;

    public static final long COUPON_ISSUED = 101L;
    public static final long COUPON_USED = 102L;
    public static final long COUPON_CANCELLED = 103L;
    public static final long COUPON_USED_THEN_CANCELLED = 104L;
    public static final long COUPON_EXPIRED = 105L;

    private static final String OPEN_AT = "2026-06-01 00:00:00.000";
    private static final String EXPIRE_OPEN = "2099-12-31 23:59:59.000";
    private static final String EXPIRE_CLOSED = "2026-07-01 00:00:00.000";

    private final JdbcTemplate jdbc;

    public InvariantFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void truncateAll() {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        con -> {
                            try (var st = con.createStatement()) {
                                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                                for (String t :
                                        List.of(
                                                "verification_violation",
                                                "verification_report",
                                                "coupon_history",
                                                "coupons",
                                                "campaign_stocks",
                                                "campaigns",
                                                "users")) {
                                    st.execute("TRUNCATE TABLE " + t);
                                }
                                st.execute("SET FOREIGN_KEY_CHECKS = 1");
                            }
                            return null;
                        });
    }

    public void build() {
        // users
        for (long u = 1; u <= 6; u++) {
            jdbc.update(
                    "INSERT INTO users (user_id, email, name, created_at) VALUES (?,?,?,?)",
                    u,
                    "fixture" + u + "@example.com",
                    "fixture-" + u,
                    OPEN_AT);
        }
        // campaigns
        jdbc.update(
                "INSERT INTO campaigns (campaign_id,name,open_at,expire_at,created_at) VALUES (?,?,?,?,?)",
                CAMPAIGN_OPEN,
                "진행 중 캠페인",
                OPEN_AT,
                EXPIRE_OPEN,
                OPEN_AT);
        jdbc.update(
                "INSERT INTO campaigns (campaign_id,name,open_at,expire_at,created_at) VALUES (?,?,?,?,?)",
                CAMPAIGN_CLOSED,
                "종료된 캠페인",
                OPEN_AT,
                EXPIRE_CLOSED,
                OPEN_AT);
        // campaign_stocks
        jdbc.update(
                "INSERT INTO campaign_stocks (stock_id,campaign_id,route_id,fare_class,total_stock,remaining_stock,created_at) VALUES (?,?,?,?,?,?,?)",
                STOCK_OPEN,
                CAMPAIGN_OPEN,
                "JEJU",
                "ECONOMY",
                10,
                6,
                OPEN_AT);
        jdbc.update(
                "INSERT INTO campaign_stocks (stock_id,campaign_id,route_id,fare_class,total_stock,remaining_stock,created_at) VALUES (?,?,?,?,?,?,?)",
                STOCK_CLOSED,
                CAMPAIGN_CLOSED,
                "BUSAN",
                "ECONOMY",
                10,
                9,
                OPEN_AT);

        coupon(
                COUPON_ISSUED,
                1,
                CAMPAIGN_OPEN,
                STOCK_OPEN,
                "ISSUED",
                EXPIRE_OPEN,
                null,
                null,
                null);
        coupon(
                COUPON_USED,
                2,
                CAMPAIGN_OPEN,
                STOCK_OPEN,
                "USED",
                EXPIRE_OPEN,
                "2026-06-03 00:00:00.000",
                null,
                null);
        coupon(
                COUPON_CANCELLED,
                3,
                CAMPAIGN_OPEN,
                STOCK_OPEN,
                "CANCELLED",
                EXPIRE_OPEN,
                null,
                "2026-06-04 00:00:00.000",
                null);
        coupon(
                COUPON_USED_THEN_CANCELLED,
                4,
                CAMPAIGN_OPEN,
                STOCK_OPEN,
                "CANCELLED",
                EXPIRE_OPEN,
                "2026-06-03 00:00:00.000",
                "2026-06-05 00:00:00.000",
                null);
        coupon(
                COUPON_EXPIRED,
                5,
                CAMPAIGN_CLOSED,
                STOCK_CLOSED,
                "EXPIRED",
                EXPIRE_CLOSED,
                null,
                null,
                EXPIRE_CLOSED);

        history(COUPON_ISSUED, null, "ISSUED", "2026-06-02 00:00:00.000");
        history(COUPON_USED, null, "ISSUED", "2026-06-02 00:00:00.000");
        history(COUPON_USED, "ISSUED", "USED", "2026-06-03 00:00:00.000");
        history(COUPON_CANCELLED, null, "ISSUED", "2026-06-02 00:00:00.000");
        history(COUPON_CANCELLED, "ISSUED", "CANCELLED", "2026-06-04 00:00:00.000");
        history(COUPON_USED_THEN_CANCELLED, null, "ISSUED", "2026-06-02 00:00:00.000");
        history(COUPON_USED_THEN_CANCELLED, "ISSUED", "USED", "2026-06-03 00:00:00.000");
        history(COUPON_USED_THEN_CANCELLED, "USED", "CANCELLED", "2026-06-05 00:00:00.000");
        history(COUPON_EXPIRED, null, "ISSUED", "2026-06-02 00:00:00.000");
        history(COUPON_EXPIRED, "ISSUED", "EXPIRED", EXPIRE_CLOSED);
    }

    private void coupon(
            long id,
            long userId,
            long campaignId,
            long stockId,
            String status,
            String expireAt,
            String usedAt,
            String cancelledAt,
            String expiredAt) {
        jdbc.update(
                """
            INSERT INTO coupons (coupon_id,user_id,campaign_id,stock_id,status,
                                 issued_at,used_at,cancelled_at,expired_at,expire_at)
            VALUES (?,?,?,?,?, '2026-06-02 00:00:00.000', ?,?,?,?)
            """,
                id,
                userId,
                campaignId,
                stockId,
                status,
                usedAt,
                cancelledAt,
                expiredAt,
                expireAt);
    }

    private final AtomicLong keySeq = new AtomicLong();

    private void history(long couponId, String from, String to, String eventAt) {
        jdbc.update(
                """
            INSERT INTO coupon_history (coupon_id,from_status,to_status,idempotency_key,event_at)
            VALUES (?,?,?,?,?)
            """,
                couponId,
                from,
                to,
                "fx-" + keySeq.incrementAndGet(),
                eventAt);
    }
}
