package com.uply.coupon.it;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CouponIntegrationFixture {

    public static final long CAMPAIGN_ID = 9001L;
    public static final long STOCK_ID = 9001L;
    public static final String ROUTE = "JEJU";
    public static final String FARE = "ECONOMY";

    /** 자식 -> 부모 순서. FOREIGN_KEY_CHECKS 를 끄므로 순서 자체가 필수는 아니지만, 의도를 남긴다. */
    private static final List<String> TABLES =
            List.of(
                    "verification_violation",
                    "verification_report",
                    "coupon_history",
                    "coupons",
                    "campaign_stocks",
                    "campaigns",
                    "users");

    private final JdbcTemplate jdbc;

    public CouponIntegrationFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 회차 데이터를 비운다.
     *
     * <p>반드시 하나의 커넥션 안에서 실행해야 한다. FOREIGN_KEY_CHECKS 는 세션 변수라서, jdbc.execute(String) 을 여러 번 호출하면 각
     * 문장이 풀에서 서로 다른 커넥션을 빌릴 수 있고 그러면 TRUNCATE 시점에는 검사가 다시 켜져 있다. 그래서 ConnectionCallback 을 쓴다.
     *
     * <p>람다 파라미터에 Connection 타입을 명시하는 이유는 오버로드 때문이다. JdbcTemplate.execute 는 ConnectionCallback 과
     * StatementCallback 두 가지를 받는데, 타입 없는 람다는 둘 다에 맞아 ambiguous 가 된다.
     */
    public void reset() {
        jdbc.execute(
                (Connection connection) -> {
                    try (Statement st = connection.createStatement()) {
                        st.execute("SET FOREIGN_KEY_CHECKS = 0");
                        try {
                            for (String table : TABLES) {
                                st.execute("TRUNCATE TABLE " + table);
                            }
                        } finally {
                            // 예외로 빠져나가도 세션 변수를 되돌린다.
                            // 이 커넥션은 풀로 반납돼 다음 테스트가 그대로 집어간다.
                            // FK 검사가 꺼진 채 반납되면, 뒤에 오는 테스트가 FK 위반을
                            // 조용히 통과시킨다.
                            st.execute("SET FOREIGN_KEY_CHECKS = 1");
                        }
                    }
                    return null;
                });
    }

    public void createCampaign(int stock) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        LocalDateTime expire = now.plusHours(2);

        jdbc.update(
                """
                INSERT INTO campaigns
                    (campaign_id, name, open_at, expire_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                CAMPAIGN_ID,
                "integration-campaign",
                now,
                expire,
                now);

        jdbc.update(
                """
                INSERT INTO campaign_stocks
                    (stock_id, campaign_id, route_id, fare_class,
                     total_stock, remaining_stock, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                STOCK_ID,
                CAMPAIGN_ID,
                ROUTE,
                FARE,
                stock,
                stock,
                now);
    }

    public void createUsers(int count, long startId) {
        for (int i = 0; i < count; i++) {
            long userId = startId + i;
            jdbc.update(
                    """
                    INSERT INTO users (user_id, email, name, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    userId,
                    "integration-" + userId + "@example.com",
                    "integration-" + userId,
                    LocalDateTime.now());
        }
    }

    public void setRemaining(long remaining) {
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = ? WHERE stock_id = ?",
                remaining,
                STOCK_ID);
    }

    public long couponCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupons WHERE stock_id = ?", Long.class, STOCK_ID);
    }

    public long historyCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_history WHERE coupon_id IN "
                        + "(SELECT coupon_id FROM coupons WHERE stock_id = ?)",
                Long.class,
                STOCK_ID);
    }

    public long remaining() {
        return jdbc.queryForObject(
                "SELECT remaining_stock FROM campaign_stocks WHERE stock_id = ?",
                Long.class,
                STOCK_ID);
    }
}
