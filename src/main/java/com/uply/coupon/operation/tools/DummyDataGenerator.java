package com.uply.coupon.operation.tools;

import com.uply.coupon.coupon.domain.CouponStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 검증 배치용 더미데이터 생성기.
 *
 * <pre>
 * 검증용 (기본값)
 *   --spring.profiles.active=dummy --users=100000 --coupons=100000 --seed=42 --truncate
 *
 * 검증기 자체 검증 (오염 5건 주입)
 *   ... --corrupt=5
 *
 * 부하 테스트 시드 (테스트 설계서 6.2 / 9절)
 *   --spring.profiles.active=dummy --users=20000 --campaigns=1 --routes=1
 *   --fare-classes=1 --stock=10000 --coupons=0 --truncate
 * </pre>
 *
 * 지켜야 하는 원칙 세 가지
 *
 * <ul>
 *   <li><b>시드 고정</b> — 같은 인자면 같은 데이터. 데이터 대신 생성기와 시드를 커밋해 재현성을 코드로 보장한다.
 *   <li><b>시각 고정</b> — 모든 시각은 base-time 에서만 파생한다. {@code NOW()} 나 컬럼 DEFAULT 에 맡기면 실행할 때마다 값이 달라져
 *       재현성이 깨진다.
 *   <li><b>단일 스레드</b> — 병렬로 돌리면 난수 소비 순서가 흔들려 재현성이 깨진다.
 * </ul>
 */
@Component
@Profile("dummy")
@RequiredArgsConstructor
public class DummyDataGenerator implements CommandLineRunner {

    /** 배치 커밋 단위. rewriteBatchedStatements=true 와 짝을 이룬다. */
    private static final int CHUNK = 1000;

    /** 초기화 대상. 자식 → 부모 순서여야 한다. verification_* 는 결과라 건드리지 않는다. */
    private static final String[] TRUNCATE_TARGETS = {
        "coupon_history", "coupons", "campaign_stocks", "campaigns", "users"
    };

    private static final String[] ROUTES = {"JEJU", "BUSAN", "FUKUOKA", "BANGKOK", "DANANG"};
    private static final String[] FARE_CLASSES = {"ECONOMY", "BUSINESS"};

    /** 최종 상태 분포(%). 누적값으로 비교한다. ISSUED 70 / USED 20 / CANCELLED 5 / EXPIRED 5 */
    private static final int P_ISSUED = 70;

    private static final int P_USED = 90;
    private static final int P_CANCELLED = 95;

    /** 발급 시각을 base-time 이후 이 범위 안에서 흩는다. */
    private static final int ISSUE_SPREAD_DAYS = 14;

    private static final int CHANGE_WINDOW_DAYS = 14;

    /**
     * 종료 캠페인의 기간. ISSUE_SPREAD_DAYS + CHANGE_WINDOW_DAYS(=28일)보다 커야 사용·취소가 유효기간 안에서 일어나 논리가 맞는다.
     */
    private static final int CLOSED_CAMPAIGN_DAYS = 30;

    /** 진행 중 캠페인의 기간. 오늘보다 확실히 미래여야 발급이 거부되지 않는다. */
    private static final int OPEN_CAMPAIGN_DAYS = 365;

    private final DataSource dataSource;
    private final ApplicationArguments appArgs;

    private long userCount;
    private long campaignCount;
    private long couponCount;
    private int routeCount;
    private int fareClassCount;
    private int totalStock;
    private int corruptCount;
    private long seed;
    private LocalDateTime baseTime;
    private boolean truncate;

    @Override
    public void run(String... args) throws Exception {
        parseArgs();
        printHeader();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            if (truncate) {
                truncateAll(conn);
            }

            timed("users", () -> insertUsers(conn));
            timed("campaigns", () -> insertCampaigns(conn));
            timed("campaign_stocks", () -> insertCampaignStocks(conn));

            if (couponCount > 0) {
                timed("coupons", () -> insertCoupons(conn));
                timed("remaining_stock", () -> updateRemainingStock(conn));
            }

            if (corruptCount > 0) {
                timed("corrupt", () -> corrupt(conn));
            }
        }
    }

    // ────────────────────────────── 인자 ──────────────────────────────

    private void parseArgs() {
        userCount = argLong("users", 1000);
        campaignCount = argLong("campaigns", 30);
        couponCount = argLong("coupons", 100_000);
        routeCount = (int) argLong("routes", ROUTES.length);
        fareClassCount = (int) argLong("fare-classes", FARE_CLASSES.length);
        totalStock = (int) argLong("stock", 12_500);
        corruptCount = (int) argLong("corrupt", 0);
        seed = argLong("seed", 42);
        baseTime = LocalDateTime.parse(arg("base-time", "2026-06-01T00:00:00"));
        truncate = appArgs.containsOption("truncate");

        if (routeCount < 1 || routeCount > ROUTES.length) {
            throw new IllegalArgumentException("--routes 는 1~" + ROUTES.length + " 범위여야 합니다");
        }
        if (fareClassCount < 1 || fareClassCount > FARE_CLASSES.length) {
            throw new IllegalArgumentException(
                    "--fare-classes 는 1~" + FARE_CLASSES.length + " 범위여야 합니다");
        }
        if (totalStock < 1) {
            throw new IllegalArgumentException("--stock 은 1 이상이어야 합니다 (ck_stock_total)");
        }
    }

    private String arg(String name, String defaultValue) {
        List<String> values = appArgs.getOptionValues(name);
        return (values == null || values.isEmpty()) ? defaultValue : values.get(0);
    }

    private long argLong(String name, long defaultValue) {
        return Long.parseLong(arg(name, String.valueOf(defaultValue)));
    }

    /**
     * 구간별로 독립된 난수원을 만든다.
     *
     * <p>전역 Random 하나를 모든 구간이 나눠 쓰면, users 개수만 바꿔도 그 뒤 구간의 난수 소비 위치가 통째로 밀려서 coupons 데이터가 전부 달라진다.
     */
    private Random sectionRandom(int section) {
        return new Random(seed * 1_000_003L + section);
    }

    // ────────────────────────────── 초기화 ──────────────────────────────

    /**
     * FK 참조 대상 테이블은 자식이 비어 있어도 TRUNCATE 가 거부된다(ERROR 1701). MySQL 은 자식 행 존재 여부를 보지 않고 FK 관계의 존재만 보기
     * 때문에 FOREIGN_KEY_CHECKS 를 꺼야 한다.
     */
    private void truncateAll(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : TRUNCATE_TARGETS) {
                    st.execute("TRUNCATE TABLE " + table);
                }
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
        conn.commit();
        System.out.println("[truncate] " + String.join(", ", TRUNCATE_TARGETS));
    }

    // ────────────────────────────── users ──────────────────────────────

    private int insertUsers(Connection conn) throws SQLException {
        final String sql =
                "INSERT INTO users (user_id, email, name, created_at) VALUES (?, ?, ?, ?)";

        int inserted = 0;
        int pending = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long i = 1; i <= userCount; i++) {
                ps.setLong(1, i);
                // uk_users_email 이 UNIQUE 라 유일해야 한다. 순차 문자열이면 충돌이 구조적으로
                // 불가능하고 인덱스에도 순서대로 들어가서 페이지 분할이 적다.
                ps.setString(2, "user" + i + "@example.com");
                ps.setString(3, "user" + i);
                ps.setObject(4, baseTime);

                ps.addBatch();
                pending++;

                if (pending == CHUNK) {
                    inserted += flush(ps, conn);
                    pending = 0;
                }
                if (i % 10_000 == 0) {
                    System.out.printf("  users %,d / %,d%n", i, userCount);
                }
            }
            if (pending > 0) {
                inserted += flush(ps, conn);
            }
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        return inserted;
    }

    // ────────────────────────── 캠페인 기간 ──────────────────────────

    /** 캠페인의 절반은 이미 끝난 기간, 절반은 진행 중으로 둔다. */
    private boolean isClosedCampaign(long campaignId) {
        return campaignId % 2 == 0;
    }

    private LocalDateTime campaignOpenAt(long campaignId) {
        return baseTime;
    }

    /**
     * 쿠폰의 만료 시각은 여기서만 나온다 insertCampaigns 와 insertCoupons 가 같은 함수를 부르게 해서, 두 곳에 같은 계산을 복사해두었다가 한쪽만
     * 고치는 일이 없게 한다.
     */
    private LocalDateTime campaignExpireAt(long campaignId) {
        return isClosedCampaign(campaignId)
                ? baseTime.plusDays(CLOSED_CAMPAIGN_DAYS)
                : baseTime.plusDays(OPEN_CAMPAIGN_DAYS);
    }

    // ────────────────────────── campaigns ──────────────────────────

    /** ck_campaign_period CHECK (expire_at > open_at) 이 걸려 있으므로 순서에 주의. */
    private int insertCampaigns(Connection conn) throws SQLException {
        final String sql =
                "INSERT INTO campaigns (campaign_id, name, open_at, expire_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)";
        long closed = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long c = 1; c <= campaignCount; c++) {
                ps.setLong(1, c);
                ps.setString(2, "특가 캠페인 " + c + "회차");
                ps.setObject(3, campaignOpenAt(c));
                ps.setObject(4, campaignExpireAt(c));
                ps.setObject(5, baseTime);
                ps.addBatch();
                if (isClosedCampaign(c)) {
                    closed++;
                }
            }
            int inserted = ps.executeBatch().length;
            conn.commit();

            System.out.printf("  진행 중 %,d개 / 종료 %,d개%n", inserted - closed, closed);

            // 종료 캠페인의 만료 시각이 아직 미래면 만료 배치가 처리할 게 없다.
            // 데이터가 아니라 안내에만 쓰는 값이라 재현성에는 영향이 없다.
            LocalDateTime closedExpireAt = baseTime.plusDays(CLOSED_CAMPAIGN_DAYS);
            if (closed > 0 && closedExpireAt.isAfter(LocalDateTime.now())) {
                System.out.println("  [경고] 종료 캠페인의 만료 시각이 " + closedExpireAt + " 로 아직 미래입니다.");
                System.out.println(
                        "         만료 배치가 처리할 대상이 없습니다. --base-time 을 "
                                + CLOSED_CAMPAIGN_DAYS
                                + "일 이상 과거로 잡으세요.");
            }
            return inserted;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    // ────────────────────── campaign_stocks ──────────────────────

    /**
     * 재고 풀 = 캠페인 × 노선 × 좌석등급.
     *
     * <p>쿠폰을 아직 넣기 전이므로 remaining_stock = total_stock 으로 둔다. 쿠폰 적재가 끝난 뒤 updateRemainingStock() 에서
     * 실제 발급 수를 빼서 확정한다.
     */
    private int insertCampaignStocks(Connection conn) throws SQLException {
        final String sql =
                "INSERT INTO campaign_stocks "
                        + "(stock_id, campaign_id, route_id, fare_class, total_stock, remaining_stock, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        long stockId = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long c = 1; c <= campaignCount; c++) {
                for (int r = 0; r < routeCount; r++) {
                    for (int f = 0; f < fareClassCount; f++) {
                        stockId++;
                        ps.setLong(1, stockId);
                        ps.setLong(2, c);
                        ps.setString(3, ROUTES[r]);
                        ps.setString(4, FARE_CLASSES[f]);
                        ps.setInt(5, totalStock);
                        ps.setInt(6, totalStock);
                        ps.setObject(7, baseTime);
                        ps.addBatch();
                    }
                }
            }
            int inserted = ps.executeBatch().length;
            conn.commit();
            return inserted;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    // ─────────────────── coupons + coupon_history ───────────────────

    /**
     * 쿠폰 한 장의 "일생" 을 시뮬레이션해서 coupons 와 coupon_history 를 동시에 만든다.
     *
     * <p>status 와 이력을 따로 랜덤으로 찍으면 {@code status=USED / 이력 마지막=CANCELLED} 같은 행이 생겨 INV-04 가 즉시 FAIL
     * 한다. 그러면 검증기가 틀린 건지 데이터가 틀린 건지 구분할 수 없다. 실제 전이를 순서대로 재생하면 INV-04·05·06·07 이 구조적으로 만족된다.
     *
     * <p>유저는 캠페인 안에서 1번부터 순차 배정한다. 캠페인이 다르면 같은 유저를 다시 써도 {@code UNIQUE(campaign_id, user_id)} 에 걸리지
     * 않으므로, 중복 체크 자료구조가 필요 없다.
     */
    private int insertCoupons(Connection conn) throws SQLException {
        final int poolsPerCampaign = routeCount * fareClassCount;
        final long poolCount = campaignCount * poolsPerCampaign;
        final long perPool = couponCount / poolCount;
        final long perCampaign = perPool * poolsPerCampaign;
        final long actualTotal = perPool * poolCount;

        if (perPool < 1) {
            throw new IllegalArgumentException(
                    "--coupons(" + couponCount + ") 가 재고 풀 수(" + poolCount + ") 보다 적습니다");
        }
        if (perPool > totalStock) {
            throw new IllegalArgumentException(
                    "풀당 쿠폰 " + perPool + "장 > total_stock " + totalStock + " — INV-01 위반이 됩니다");
        }
        if (perCampaign > userCount) {
            throw new IllegalArgumentException(
                    "캠페인당 쿠폰 "
                            + perCampaign
                            + "장 > 유저 "
                            + userCount
                            + "명 — UNIQUE(campaign_id, user_id) 를 만족할 수 없습니다");
        }
        if (actualTotal != couponCount) {
            System.out.printf(
                    "  요청 %,d장 → 실제 %,d장 (풀당 %,d장 × 풀 %,d개)%n",
                    couponCount, actualTotal, perPool, poolCount);
        }

        final String couponSql =
                "INSERT INTO coupons (coupon_id, user_id, campaign_id, stock_id, status, "
                        + "issued_at, used_at, cancelled_at, expired_at, expire_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        final String historySql =
                "INSERT INTO coupon_history "
                        + "(coupon_id, from_status, to_status, idempotency_key, event_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)";

        final Random rnd = sectionRandom(3);
        long couponId = 0;
        long historyRows = 0;
        int pending = 0;

        try (PreparedStatement pc = conn.prepareStatement(couponSql);
                PreparedStatement ph = conn.prepareStatement(historySql)) {

            for (long c = 1; c <= campaignCount; c++) {
                long userInCampaign = 0;
                long stockBase = (c - 1) * poolsPerCampaign;

                for (int p = 1; p <= poolsPerCampaign; p++) {
                    long stockId = stockBase + p;

                    for (long k = 0; k < perPool; k++) {
                        couponId++;
                        userInCampaign++;

                        // ① 발급 — 캠페인 오픈 이후, ISSUE_SPREAD_DAYS 안에서
                        LocalDateTime issuedAt =
                                campaignOpenAt(c)
                                        .plusSeconds(rnd.nextInt(ISSUE_SPREAD_DAYS * 24 * 3600));

                        // ② 만료 시각은 캠페인에서 상속한다 (정책 C-1 / 인수 기준 E-4).
                        //    발급 시각 기준 상대값으로 계산하면 같은 캠페인 쿠폰끼리 만료일이 달라진다.
                        LocalDateTime expireAt = campaignExpireAt(c);

                        // ③ 최종 상태를 분포에서 뽑되, 허용된 전이만 밟는다
                        CouponStatus status = pickStatus(rnd);

                        // 아직 기간이 남은 캠페인의 쿠폰은 만료될 수 없다.
                        // 억지로 EXPIRED 로 만들면 expired_at 이 expire_at 보다 앞서게 되어
                        // INV-06(시각 순서)을 위반한다.
                        if (status == CouponStatus.EXPIRED && !isClosedCampaign(c)) {
                            status = CouponStatus.ISSUED;
                        }

                        LocalDateTime usedAt = null;
                        LocalDateTime cancelledAt = null;
                        LocalDateTime expiredAt = null;

                        final int halfWindow = CHANGE_WINDOW_DAYS / 2 * 24 * 3600;

                        if (status == CouponStatus.EXPIRED) {
                            // 만료 처리는 유효기간이 지난 뒤에 일어난다 (INV-06)
                            expiredAt = expireAt.plusMinutes(1 + rnd.nextInt(60));

                        } else if (status == CouponStatus.USED) {
                            // 사용은 발급 후 CHANGE_WINDOW_DAYS 안에서 일어난다.
                            // 발급 창(14일) + 변경 창(14일) = 28일 < 종료 캠페인 기간(30일)이라
                            // 유효기간이 지난 뒤에 사용되는 일은 생기지 않는다.
                            usedAt =
                                    issuedAt.plusSeconds(
                                            60 + rnd.nextInt(CHANGE_WINDOW_DAYS * 24 * 3600));

                        } else if (status == CouponStatus.CANCELLED) {
                            // 취소에는 두 경로가 있다 (팀 정책).
                            //   항공사 일괄 취소 : ISSUED -> CANCELLED         (used_at 없음)
                            //   예매 취소        : ISSUED -> USED -> CANCELLED (used_at 유지)
                            // 둘 다 만들어야 INV-05 의 USED->CANCELLED 전이와
                            // INV-07 의 완화된 CANCELLED 분기가 실제 데이터로 검증된다.
                            // 한쪽만 만들면 규칙이 "통과" 해도 그 경로를 한 번도 안 본 것이다.
                            if (rnd.nextBoolean()) {
                                usedAt = issuedAt.plusSeconds(60 + rnd.nextInt(halfWindow));
                                cancelledAt = usedAt.plusSeconds(60 + rnd.nextInt(halfWindow));
                            } else {
                                cancelledAt =
                                        issuedAt.plusSeconds(
                                                60 + rnd.nextInt(CHANGE_WINDOW_DAYS * 24 * 3600));
                            }
                        }
                        pc.setLong(1, couponId);
                        pc.setLong(2, userInCampaign);
                        pc.setLong(3, c);
                        pc.setLong(4, stockId);
                        pc.setString(5, status.name());
                        pc.setObject(6, issuedAt);
                        setNullableTime(pc, 7, usedAt);
                        setNullableTime(pc, 8, cancelledAt);
                        setNullableTime(pc, 9, expiredAt);
                        pc.setObject(10, expireAt);
                        // created_at 을 컬럼 DEFAULT(CURRENT_TIMESTAMP)에 맡기면 실행할 때마다
                        // 값이 달라져 재현성이 깨진다. 반드시 명시한다.
                        pc.setObject(11, issuedAt);
                        pc.addBatch();

                        // 발급 이력 (from_status 는 NULL)
                        ph.setLong(1, couponId);
                        ph.setNull(2, Types.VARCHAR);
                        ph.setString(3, CouponStatus.ISSUED.name());
                        ph.setString(4, "dummy-" + couponId + "-1");
                        ph.setObject(5, issuedAt);
                        ph.setObject(6, issuedAt);
                        ph.addBatch();
                        historyRows++;

                        // 사용 이력 — USED 이거나, 예매 취소(USED 를 거친 CANCELLED)
                        if (usedAt != null) {
                            ph.setLong(1, couponId);
                            ph.setString(2, CouponStatus.ISSUED.name());
                            ph.setString(3, CouponStatus.USED.name());
                            ph.setString(4, "dummy-" + couponId + "-2");
                            ph.setObject(5, usedAt);
                            ph.setObject(6, usedAt);
                            ph.addBatch();
                            historyRows++;
                        }

                        // 종료 이력 — 취소 또는 만료. from_status 는 직전 상태를 따른다.
                        if (cancelledAt != null) {
                            ph.setLong(1, couponId);
                            ph.setString(
                                    2,
                                    usedAt != null
                                            ? CouponStatus.USED.name()
                                            : CouponStatus.ISSUED.name());
                            ph.setString(3, CouponStatus.CANCELLED.name());
                            ph.setString(4, "dummy-" + couponId + "-3");
                            ph.setObject(5, cancelledAt);
                            ph.setObject(6, cancelledAt);
                            ph.addBatch();
                            historyRows++;
                        } else if (expiredAt != null) {
                            ph.setLong(1, couponId);
                            ph.setString(2, CouponStatus.ISSUED.name());
                            ph.setString(3, CouponStatus.EXPIRED.name());
                            ph.setString(4, "dummy-" + couponId + "-3");
                            ph.setObject(5, expiredAt);
                            ph.setObject(6, expiredAt);
                            ph.addBatch();
                            historyRows++;
                        }

                        pending++;
                        if (pending == CHUNK) {
                            flushCouponChunk(pc, ph, conn);
                            pending = 0;
                        }
                        if (couponId % 10_000 == 0) {
                            System.out.printf("  coupons %,d / %,d%n", couponId, actualTotal);
                        }
                    }
                }
            }

            if (pending > 0) {
                flushCouponChunk(pc, ph, conn);
            }
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }

        System.out.printf("  coupon_history %,d건%n", historyRows);
        return (int) couponId;
    }

    /** FK 때문에 부모(coupons)를 먼저 실행해야 자식(coupon_history)이 들어간다. */
    private void flushCouponChunk(PreparedStatement pc, PreparedStatement ph, Connection conn)
            throws SQLException {
        pc.executeBatch();
        ph.executeBatch();
        conn.commit();
    }

    private CouponStatus pickStatus(Random rnd) {
        int r = rnd.nextInt(100);
        if (r < P_ISSUED) {
            return CouponStatus.ISSUED;
        }
        if (r < P_USED) {
            return CouponStatus.USED;
        }
        if (r < P_CANCELLED) {
            return CouponStatus.CANCELLED;
        }
        return CouponStatus.EXPIRED;
    }

    private void setNullableTime(PreparedStatement ps, int index, LocalDateTime value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setObject(index, value);
        }
    }

    // ────────────────────── remaining_stock ──────────────────────

    /** 취소·만료는 재고 소멸 정책이므로 상태를 보지 않는다. USED 든 CANCELLED 든 EXPIRED 든 전부 "발급된 것" 이다. (공통 협업 기준 3번) */
    private int updateRemainingStock(Connection conn) throws SQLException {
        final String sql =
                "UPDATE campaign_stocks s SET s.remaining_stock = s.total_stock "
                        + "- (SELECT COUNT(*) FROM coupons c WHERE c.stock_id = s.stock_id)";
        try (Statement st = conn.createStatement()) {
            int updated = st.executeUpdate(sql);
            conn.commit();
            return updated;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    // ────────────────────────── corrupt ──────────────────────────

    /**
     * 검증기 자체 검증용 오염 주입.
     *
     * <p>"위반 0건" 은 검증기가 아무것도 안 해도 나온다. 정상에서 0건, 오염 N건에서 정확히 N건이 나와야 검증기가 작동한다는 증거가 된다.
     *
     * <p>USED 쿠폰의 status 만 CANCELLED 로 바꾸고 타임스탬프도 같이 옮긴다. 그러면 INV-04(현재 상태 ≠ 이력 최종)만 걸리고 INV-06·07
     * 은 여전히 통과한다 — 규칙 하나만 정확히 겨냥해야 "오탐 없음" 을 함께 증명할 수 있다.
     */
    private int corrupt(Connection conn) throws SQLException {
        List<Long> targets = new ArrayList<>();

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT coupon_id FROM coupons WHERE status = 'USED' "
                                + "ORDER BY coupon_id LIMIT ?")) {
            ps.setInt(1, corruptCount);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    targets.add(rs.getLong(1));
                }
            }
        }

        if (targets.size() < corruptCount) {
            throw new IllegalStateException(
                    "오염 대상이 부족합니다. USED 쿠폰 " + targets.size() + "건 < 요청 " + corruptCount + "건");
        }

        try (PreparedStatement ps =
                conn.prepareStatement(
                        "UPDATE coupons SET status = 'CANCELLED', cancelled_at = used_at, "
                                + "used_at = NULL WHERE coupon_id = ?")) {
            for (Long id : targets) {
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }

        System.out.println("  오염 주입 대상 coupon_id = " + targets);
        System.out.println("  → INV-04 가 정확히 " + targets.size() + "건 검출해야 하고,");
        System.out.println("    INV-01/02/03/06/07 은 여전히 0건이어야 한다 (오탐 없음)");
        return targets.size();
    }

    // ────────────────────────────── 공통 ──────────────────────────────

    /** rewriteBatchedStatements 가 켜져 있으면 반환값이 SUCCESS_NO_INFO(-2) 일 수 있어 길이로 센다. */
    private int flush(PreparedStatement ps, Connection conn) throws SQLException {
        int count = ps.executeBatch().length;
        conn.commit();
        return count;
    }

    @FunctionalInterface
    private interface SqlStep {
        int run() throws SQLException;
    }

    private void timed(String label, SqlStep step) throws SQLException {
        long t0 = System.currentTimeMillis();
        int rows = step.run();
        long elapsed = System.currentTimeMillis() - t0;
        double rowsPerSec = rows * 1000.0 / Math.max(elapsed, 1);
        System.out.printf("[%s] %,d건 / %,dms (%,.0f rows/s)%n", label, rows, elapsed, rowsPerSec);
    }

    private void printHeader() {
        System.out.println("──────────────────────────────────────");
        System.out.printf("  users        : %,d%n", userCount);
        System.out.printf("  campaigns    : %,d%n", campaignCount);
        System.out.printf("  routes       : %d%n", routeCount);
        System.out.printf("  fare-classes : %d%n", fareClassCount);
        System.out.printf("  stock pools  : %,d%n", campaignCount * routeCount * fareClassCount);
        System.out.printf("  total_stock  : %,d%n", totalStock);
        System.out.printf("  coupons      : %,d%n", couponCount);
        System.out.printf("  corrupt      : %,d%n", corruptCount);
        System.out.printf("  seed         : %d%n", seed);
        System.out.printf("  base-time    : %s%n", baseTime);
        System.out.printf("  truncate     : %s%n", truncate);
        System.out.println("──────────────────────────────────────");
    }
}
