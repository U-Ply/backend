package com.uply.coupon.operation.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
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
 * 실행 예)
 *   --spring.profiles.active=dummy --users=100000 --seed=42 --truncate
 * </pre>
 *
 * 지켜야 하는 원칙 세 가지
 *
 * <ul>
 *   <li><b>시드 고정</b> — 같은 인자면 같은 데이터. 300만 건 CSV 는 900MB 라 커밋할 수 없으므로, 데이터 대신 생성기와 시드를 커밋해서 재현성을
 *       코드로 보장한다.
 *   <li><b>시각 고정</b> — 모든 시각은 base-time 에서만 파생한다. {@code LocalDateTime.now()} 를 쓰면 실행할 때마다 데이터가 달라져
 *       재현성이 깨진다.
 *   <li><b>단일 스레드</b> — 병렬로 돌리면 난수 소비 순서가 흔들려 재현성이 깨진다. 멘토가 적재 속도는 평가하지 않는다고 명시했으므로 손해가 없다.
 * </ul>
 */
@Component
@Profile("dummy")
@RequiredArgsConstructor
public class DummyDataGenerator implements CommandLineRunner {

    /**
     * 배치 커밋 단위.
     *
     * <p>rewriteBatchedStatements=true 가 켜져 있으면 이 CHUNK 만큼이 하나의 다중 VALUES 문장으로 합쳐져 한 번에 전송된다. 너무 크면
     * max_allowed_packet(기본 64MB)에 걸리고, 너무 작으면 왕복이 다시 늘어난다. 300만 건 확장 시 5000~10000 으로 올려보며 튜닝할 것.
     */
    private static final int CHUNK = 1000;

    /** 초기화 대상. 자식 → 부모 순서여야 한다. verification_* 는 결과라 건드리지 않는다. */
    private static final String[] TRUNCATE_TARGETS = {
        "coupon_history", "coupons", "campaign_stocks", "campaigns", "users"
    };

    private final DataSource dataSource;
    private final ApplicationArguments appArgs;

    private long userCount;
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

            long t0 = System.currentTimeMillis();
            int inserted = insertUsers(conn);
            report("users", inserted, System.currentTimeMillis() - t0);

            // 다음 단계가 여기에 붙는다
            //   insertCampaigns(conn);
            //   insertCoupons(conn);       ← 상태 머신 시뮬레이션
            //   updateRemainingStock(conn);
            //   corrupt(conn);
        }
    }

    // ────────────────────────────── 인자 ──────────────────────────────

    private void parseArgs() {
        userCount = argLong("users", 1000);
        seed = argLong("seed", 42);
        baseTime = LocalDateTime.parse(arg("base-time", "2026-08-01T00:00:00"));
        truncate = appArgs.containsOption("truncate");
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
     * 구간마다 시드를 파생시키면 한 구간의 규모를 바꿔도 다른 구간은 그대로 재현된다.
     */
    private Random sectionRandom(int section) {
        return new Random(seed * 1_000_003L + section);
    }

    // ────────────────────────────── 초기화 ──────────────────────────────

    /**
     * FK 참조 대상 테이블은 자식이 비어 있어도 TRUNCATE 가 거부된다(ERROR 1701). MySQL 은 자식 행 존재 여부를 보지 않고 FK 관계의 존재만 보기
     * 때문에, FOREIGN_KEY_CHECKS 를 꺼야 한다.
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
                // uk_users_email 이 UNIQUE 라 유일해야 한다. 순차 문자열이면 충돌이 구조적으로 불가능하고
                // 인덱스에도 순서대로 들어가서 페이지 분할이 적다.
                ps.setString(2, "user" + i + "@example.com");
                ps.setString(3, "user" + i);
                // setTimestamp 가 아니라 setObject(LocalDateTime). Timestamp 는 시간대 변환이 끼어들어
                // 실행 환경에 따라 값이 달라진다.
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

            // userCount 가 CHUNK 로 나누어떨어지지 않으면 마지막 조각이 남는다.
            // 10만처럼 딱 떨어지는 값으로만 테스트하면 이 누락이 드러나지 않으므로
            // --users=99999 로 한 번 검증할 것.
            if (pending > 0) {
                inserted += flush(ps, conn);
            }
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }

        return inserted;
    }

    // ────────────────────────────── 공통 ──────────────────────────────

    /** rewriteBatchedStatements 가 켜져 있으면 반환값이 SUCCESS_NO_INFO(-2) 일 수 있어 길이로 센다. */
    private int flush(PreparedStatement ps, Connection conn) throws SQLException {
        int count = ps.executeBatch().length;
        conn.commit();
        return count;
    }

    private void printHeader() {
        System.out.println("──────────────────────────────────────");
        System.out.printf("  users     : %,d%n", userCount);
        System.out.printf("  seed      : %d%n", seed);
        System.out.printf("  base-time : %s%n", baseTime);
        System.out.printf("  truncate  : %s%n", truncate);
        System.out.println("──────────────────────────────────────");
    }

    private void report(String label, int rows, long elapsedMs) {
        double rowsPerSec = rows * 1000.0 / Math.max(elapsedMs, 1);
        System.out.printf("[%s] %,d건 / %,dms (%,.0f rows/s)%n", label, rows, elapsedMs, rowsPerSec);
    }
}
