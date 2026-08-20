package com.uply.coupon.operation.verification.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uply.coupon.operation.verification.InvariantFixture;
import com.uply.coupon.operation.verification.batch.VerificationRunner;
import com.uply.coupon.operation.verification.domain.RoundVersion;
import com.uply.coupon.operation.verification.domain.RuleResult;
import com.uply.coupon.operation.verification.domain.VerificationRun;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(InvariantFixture.class)
// @Transactional 절대 금지 — runAll() 이 REQUIRES_NEW 라 미커밋 주입을 못 본다
@DisplayName("규칙 검출력")
class InvariantDetectionTest {

    @Autowired VerificationRunner runner;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvariantFixture fixture;

    @BeforeEach
    void seed() {
        fixture.truncateAll();
        fixture.build();
    }

    @AfterEach
    void clean() {
        fixture.truncateAll();
    }

    /** 미탐(해당 규칙이 잡히는가)과 오탐(다른 규칙이 안 잡히는가)을 한 번에 단정한다. */
    private void assertOnlyViolated(String runId, String... expected) {
        VerificationRun run = runner.runAll(runId, RoundVersion.V1);
        List<String> actual =
                run.results().stream()
                        .filter(RuleResult::isInvariant) // CLOCK-* 은 실제 시계라 제외
                        .filter(r -> !r.passed())
                        .map(RuleResult::code)
                        .sorted()
                        .toList();
        assertThat(actual)
                .as("검출된 규칙 (기대: %s)", Arrays.toString(expected))
                .containsExactlyInAnyOrder(expected);
    }

    @Test
    @DisplayName("기준선 — 주입이 없으면 12개 규칙 전부 0")
    void 기준선() {
        assertOnlyViolated("t-baseline"); // 기대 목록 비움
    }

    @Test
    @DisplayName("INV-03 — remaining_stock 을 1 늘리면 재고 카운터만 검출된다")
    void inv03() {
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = remaining_stock + 1 WHERE stock_id = ?",
                InvariantFixture.STOCK_OPEN);
        assertOnlyViolated("t-inv03", "INV-03");
    }

    @Test
    @DisplayName("INV-01 — 초과 발급은 INV-03 이 깨진 상태에서만 도달 가능하다")
    void inv01() {
        // 문서 주1: remaining = total - 발급수 가 성립하고 CHECK(remaining>=0) 이 있으면
        // 발급수 <= total 이 자동 보장된다. 두 규칙이 함께 켜지는 것이 정상 동작이다.
        jdbc.update(
                "UPDATE campaign_stocks SET total_stock = 3, remaining_stock = 0 WHERE stock_id = ?",
                InvariantFixture.STOCK_OPEN);
        assertOnlyViolated("t-inv01", "INV-01", "INV-03");
    }

    @Test
    @DisplayName("INV-02 — UNIQUE 제약이 중복 발급 주입 자체를 거부한다")
    void inv02() {
        // 문서 주2: 격리 주입이 불가능하다는 것이 곧 제약이 작동한다는 증거다.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO coupons (coupon_id,user_id,campaign_id,stock_id,status,issued_at,expire_at)
                SELECT 999, user_id, campaign_id, stock_id, status, issued_at, expire_at
                FROM coupons WHERE coupon_id = ?
                """,
                                        InvariantFixture.COUPON_ISSUED))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("INV-04 — 상태만 바꾸면 최종 이력과 어긋난다")
    void inv04() {
        jdbc.update(
                """
                UPDATE coupons SET status='CANCELLED', cancelled_at = used_at, used_at = NULL
                WHERE coupon_id = ?
                """,
                InvariantFixture.COUPON_USED);
        assertOnlyViolated("t-inv04", "INV-04");
    }

    @Test
    @DisplayName("INV-04 - ISSUED history event_at moved after USED history")
    void inv04_history_order_inverted() {
        // Lua 경로의 issued_at 은 Redis 시계, USED 이력은 DB 시계다.
        // 두 시계가 어긋나 순서가 뒤집히면 '최종 이력'이 ISSUED 로 잡힌다.
        jdbc.update(
                """
                UPDATE coupon_history
                   SET event_at = '2026-07-01 00:00:00.000'
                 WHERE coupon_id = ? AND to_status = 'ISSUED'
                """,
                InvariantFixture.COUPON_USED);
        assertOnlyViolated("t-inv04-order", "INV-04");
    }

    @Test
    @DisplayName("INV-05 — 허용되지 않은 전이 이력")
    void inv05() {
        // event_at 을 과거로 둬야 최종 이력이 안 바뀌어 INV-04 가 함께 켜지지 않는다. 문서 주3.
        jdbc.update(
                """
                INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
                VALUES (?, 'EXPIRED', 'USED', 'x-inv05', '2026-01-01 00:00:00.000')
                """,
                InvariantFixture.COUPON_ISSUED);
        assertOnlyViolated("t-inv05", "INV-05");
    }

    @Test
    @DisplayName("INV-06 — used_at 이 issued_at 이전")
    void inv06() {
        jdbc.update(
                "UPDATE coupons SET used_at = issued_at - INTERVAL 1 DAY WHERE coupon_id = ?",
                InvariantFixture.COUPON_USED);
        assertOnlyViolated("t-inv06", "INV-06");
    }

    @Test
    @DisplayName("INV-07 — ISSUED 인데 used_at 이 있다")
    void inv07() {
        jdbc.update(
                "UPDATE coupons SET used_at = '2026-06-10 00:00:00.000' WHERE coupon_id = ?",
                InvariantFixture.COUPON_ISSUED);
        assertOnlyViolated("t-inv07", "INV-07");
    }

    @Test
    @DisplayName("INV-08 — 다른 캠페인의 재고 풀. 재고 수를 보정해 INV-03 을 격리한다")
    void inv08() {
        // stock_id 만 옮기면 두 풀의 재고 카운터가 어긋나 INV-03 이 함께 켜진다.
        jdbc.update(
                "UPDATE coupons SET stock_id = ? WHERE coupon_id = ?",
                InvariantFixture.STOCK_CLOSED,
                InvariantFixture.COUPON_ISSUED);
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = 7 WHERE stock_id = ?",
                InvariantFixture.STOCK_OPEN);
        jdbc.update(
                "UPDATE campaign_stocks SET remaining_stock = 8 WHERE stock_id = ?",
                InvariantFixture.STOCK_CLOSED);
        assertOnlyViolated("t-inv08", "INV-08");
    }

    @Test
    @DisplayName("INV-09 — 같은 전이를 다른 키로 두 번")
    void inv09() {
        jdbc.update(
                """
                INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
                VALUES (?, NULL, 'ISSUED', 'x-inv09', '2026-01-01 00:00:00.000')
                """,
                InvariantFixture.COUPON_ISSUED);
        assertOnlyViolated("t-inv09", "INV-09");
    }

    @Test
    @DisplayName("INV-10 — 없는 쿠폰의 이력")
    void inv10() {
        // FOREIGN_KEY_CHECKS 는 세션 변수다. 커넥션이 바뀌면 무효라 같은 커넥션에서 실행한다.
        jdbc.execute(
                (ConnectionCallback<Void>)
                        con -> {
                            try (var st = con.createStatement()) {
                                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                                st.execute(
                                        """
                        INSERT INTO coupon_history (coupon_id, from_status, to_status, idempotency_key, event_at)
                        VALUES (999999, NULL, 'ISSUED', 'x-inv10', '2026-06-02 00:00:00.000')
                        """);
                                st.execute("SET FOREIGN_KEY_CHECKS = 1");
                            }
                            return null;
                        });
        assertOnlyViolated("t-inv10", "INV-10");
    }

    @Test
    @DisplayName("INV-11 — 오픈 전 발급")
    void inv11() {
        jdbc.update(
                "UPDATE coupons SET issued_at = '2026-05-01 00:00:00.000' WHERE coupon_id = ?",
                InvariantFixture.COUPON_ISSUED);
        assertOnlyViolated("t-inv11", "INV-11");
    }

    @Test
    @DisplayName("INV-12 — 쿠폰 만료 시각이 캠페인과 다르다")
    void inv12() {
        jdbc.update(
                "UPDATE coupons SET expire_at = expire_at + INTERVAL 1 DAY WHERE coupon_id = ?",
                InvariantFixture.COUPON_ISSUED);
        assertOnlyViolated("t-inv12", "INV-12");
    }
}
