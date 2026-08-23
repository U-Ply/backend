package com.uply.coupon.operation.verification.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 판정 계산 회귀 테스트.
 *
 * <p>이 클래스가 존재하는 이유는 실제 사고 때문이다. 판정이 rule_code 가 INV- 로 시작하는 규칙만 세고 있어서, REC-01 위반 1건이 있는 회차의 헤더에
 * "통과" 가 찍혔다 (docs/round-results/LEAK-V2-01.md). INV 전부 통과 + REC-01 위반이라는 조합을 아무도 보지 않았기 때문에 통과했던
 * 버그다.
 */
@ExtendWith(MockitoExtension.class)
class VerificationReportRendererTest {

    private static final String RUN_ID = "TEST-RUN-01";

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private VerificationReportRenderer renderer;

    @Test
    @DisplayName("INV 전부 통과 + REC-01 위반이면 판정이 통과가 아니다")
    void recViolationAloneMustNotReadAsPassed() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rules.add(rule(String.format("INV-%02d", i), "CHECKED", 0));
        }
        rules.add(rule("CLOCK-01", "CHECKED", 0));
        rules.add(rule("CLOCK-02", "CHECKED", 0));
        rules.add(rule("REC-01", "CHECKED", 1));

        stub(
                rules,
                List.of(violation("REC-01", "campaign_stocks", 301L, "redis=0, db=1, diff=-1")));

        String md = renderer.render(RUN_ID);

        assertThat(md).contains("| 총 위반 | 1 |");
        assertThat(md).doesNotContain("| 판정 | **통과** |");
        assertThat(md).contains("불일치");
    }

    @Test
    @DisplayName("전부 통과하면 판정이 통과다")
    void allCheckedAndCleanReadsAsPassed() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rules.add(rule(String.format("INV-%02d", i), "CHECKED", 0));
        }
        rules.add(rule("CLOCK-01", "CHECKED", 0));
        rules.add(rule("CLOCK-02", "CHECKED", 0));
        rules.add(rule("REC-01", "CHECKED", 0));

        stub(rules, List.of());

        assertThat(renderer.render(RUN_ID)).contains("| 판정 | **통과** |");
    }

    @Test
    @DisplayName("SKIPPED 가 있으면 위반 0 건이어도 통과가 아니다")
    void skippedRuleMustNotReadAsPassed() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rules.add(rule(String.format("INV-%02d", i), "CHECKED", 0));
        }
        rules.add(rule("CLOCK-01", "CHECKED", 0));
        rules.add(rule("CLOCK-02", "CHECKED", 0));
        rules.add(rule("REC-01", "SKIPPED", 0));

        stub(rules, List.of());

        String md = renderer.render(RUN_ID);
        assertThat(md).doesNotContain("| 판정 | **통과** |");
        assertThat(md).contains("불완전");
    }

    // ─────────────────────────────────────────────

    private void stub(List<Map<String, Object>> rules, List<Map<String, Object>> violations) {
        given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .willAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            return sql.contains("verification_violation") ? violations : rules;
                        });
    }

    private Map<String, Object> rule(String code, String status, int violationCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("round", "V2");
        row.put("status", status);
        row.put("rule_code", code);
        row.put("rule_name", code + " 이름");
        row.put("snapshot_at", LocalDateTime.of(2026, 8, 21, 9, 0));
        row.put("violation_count", violationCount);
        row.put("sampled_count", violationCount);
        row.put("checked_rows", 100);
        row.put("elapsed_ms", 10);
        row.put("passed", violationCount == 0 ? 1 : 0);
        return row;
    }

    private Map<String, Object> violation(String code, String table, Long id, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rule_code", code);
        row.put("target_table", table);
        row.put("target_id", id);
        row.put("detail", detail);
        return row;
    }
}
