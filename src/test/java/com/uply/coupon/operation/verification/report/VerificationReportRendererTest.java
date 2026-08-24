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
        List<Map<String, Object>> rules = allClean("V2");
        replace(rules, "REC-01", rule("REC-01", "CHECKED", 1, "V2"));

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
        stub(allClean("V2"), List.of());

        assertThat(renderer.render(RUN_ID)).contains("| 판정 | **통과** |");
    }

    @Test
    @DisplayName("SKIPPED 가 있으면 위반 0 건이어도 통과가 아니다")
    void skippedRuleMustNotReadAsPassed() {
        List<Map<String, Object>> rules = allClean("V2");
        replace(rules, "REC-01", rule("REC-01", "SKIPPED", 0, "V2"));

        stub(rules, List.of());

        String md = renderer.render(RUN_ID);

        assertThat(md).doesNotContain("| 판정 | **통과** |");
        assertThat(md).contains("불완전");
    }

    /**
     * V0 는 정합성 판정 대상이 아니다 (test-plan 5.4).
     *
     * <p>이 테스트가 지키는 것은 판정 사슬의 <b>순서</b>다. V0 분기가 failedInvariants 검사보다 앞에 있어야만 성립한다. 누가 순서를 바꾸면 위반
     * 0 건인 V0 가 "통과" 로 찍히고, 그건 "NoLock 이 정합했다" 로 읽힌다.
     *
     * <p>5.4 는 V0 의 문제가 "매 실행에서 동일하게 발생한다고 보장할 수 없다" 고 못 박으므로, 위반이 나오는 것을 판정 조건으로 걸어서도 안 된다. 위반 수는
     * §11 비교표의 기록 항목이다.
     */
    @Test
    @DisplayName("V0 는 위반 수와 무관하게 BASELINE 이고 통과·실패로 찍지 않는다")
    void v0IsRecordedNotJudged() {
        stub(allClean("V0"), List.of());

        String md = renderer.render(RUN_ID);

        assertThat(md).contains("| 판정 | **BASELINE**");
        assertThat(md).doesNotContain("| 판정 | **통과** |");
        assertThat(md).doesNotContain("| 판정 | **실패**");
    }

    @Test
    @DisplayName("V0 는 불변식 위반이 있어도 실패가 아니라 BASELINE 이다")
    void v0WithViolationIsStillBaseline() {
        List<Map<String, Object>> rules = allClean("V0");
        replace(rules, "INV-03", rule("INV-03", "CHECKED", 7, "V0"));

        stub(rules, List.of(violation("INV-03", "campaign_stocks", 301L, "remaining=-7")));

        String md = renderer.render(RUN_ID);

        assertThat(md).contains("| 판정 | **BASELINE**");
        assertThat(md).contains("| 총 위반 | 7 |");
        assertThat(md).doesNotContain("| 판정 | **실패**");
    }

    // ─────────────────────────────────────────────

    /** 해당 회차의 전 규칙이 CHECKED 이고 위반 0 건인 상태. 각 테스트는 여기서 한 줄만 갈아 끼운다. */
    private List<Map<String, Object>> allClean(String round) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rules.add(rule(String.format("INV-%02d", i), "CHECKED", 0, round));
        }
        rules.add(rule("CLOCK-01", "CHECKED", 0, round));
        rules.add(rule("CLOCK-02", "CHECKED", 0, round));
        rules.add(rule("REC-01", "CHECKED", 0, round));
        return rules;
    }

    private void replace(List<Map<String, Object>> rules, String code, Map<String, Object> row) {
        for (int i = 0; i < rules.size(); i++) {
            if (code.equals(rules.get(i).get("rule_code"))) {
                rules.set(i, row);
                return;
            }
        }
        throw new IllegalArgumentException("no such rule in the fixture: " + code);
    }

    private void stub(List<Map<String, Object>> rules, List<Map<String, Object>> violations) {
        given(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .willAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            return sql.contains("verification_violation") ? violations : rules;
                        });
    }

    private Map<String, Object> rule(String code, String status, int violationCount) {
        return rule(code, status, violationCount, "V2");
    }

    private Map<String, Object> rule(String code, String status, int violationCount, String round) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("round", round);
        row.put("status", status);
        row.put("rule_code", code);
        row.put("rule_name", code + " 이름");
        row.put("snapshot_at", LocalDateTime.of(2026, 8, 21, 9, 0));
        row.put("violation_count", violationCount);
        row.put("sampled_count", violationCount);
        row.put("checked_rows", 100);
        row.put("elapsed_ms", 10);
        // 생성 컬럼 passed 를 그대로 흉내 낸다: violation_count = 0 이면 1.
        // status 와 무관하다는 점이 중요하다 — SKIPPED 도 여기서는 1 이 된다.
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
