package com.uply.coupon.operation.verification.report;

import com.uply.coupon.operation.admin.VerificationRunNotFoundException;
import com.uply.coupon.operation.verification.domain.RoundVersion;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 검증 회차 하나를 마크다운 리포트로 만든다.
 *
 * <p>검사 범위(checked_rows)를 반드시 함께 낸다.
 */
@Component
@RequiredArgsConstructor
public class VerificationReportRenderer {

    private static final int SAMPLE_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;

    public String render(String runId) {
        List<Map<String, Object>> rules =
                jdbcTemplate.queryForList(
                        """
                        SELECT round, rule_code, rule_name, snapshot_at, violation_count,
                               sampled_count, checked_rows, elapsed_ms, passed
                        FROM verification_report
                        WHERE run_id = ?
                        ORDER BY rule_code
                        """,
                        runId);

        if (rules.isEmpty()) {
            throw new VerificationRunNotFoundException(runId);
        }

        String round = (String) rules.get(0).get("round");
        StringBuilder md = new StringBuilder();

        appendHeader(md, runId, round, rules);
        appendRuleTable(md, round, rules);
        appendViolations(md, runId);

        return md.toString();
    }

    private void appendHeader(
            StringBuilder md, String runId, String round, List<Map<String, Object>> rules) {

        long totalViolations =
                rules.stream()
                        .mapToLong(r -> ((Number) r.get("violation_count")).longValue())
                        .sum();
        long totalElapsed =
                rules.stream()
                        .mapToLong(
                                r ->
                                        r.get("elapsed_ms") == null
                                                ? 0L
                                                : ((Number) r.get("elapsed_ms")).longValue())
                        .sum();
        long invariantCount = rules.stream().filter(r -> code(r).startsWith("INV-")).count();
        long failedInvariants =
                rules.stream()
                        .filter(r -> code(r).startsWith("INV-"))
                        .filter(r -> !passed(r))
                        .count();
        boolean clockValid =
                rules.stream()
                        .filter(r -> code(r).startsWith("CLOCK-"))
                        .allMatch(VerificationReportRenderer::passed);

        String verdict;
        if (!clockValid) {
            verdict = "**무효** — 시계가 어긋나 어느 시점을 본 것인지 알 수 없다";
        } else if (failedInvariants > 0) {
            verdict = "**실패** — 불변식 " + failedInvariants + "개 위반";
        } else {
            verdict = "**통과**";
        }

        md.append("# 검증 리포트 — ").append(runId).append("\n\n");
        md.append("| 항목 | 값 |\n| --- | --- |\n");
        md.append("| runId | `").append(runId).append("` |\n");
        md.append("| 회차 | ").append(describeRound(round)).append(" |\n");
        md.append("| 스냅샷 | ").append(rules.get(0).get("snapshot_at")).append(" |\n");
        md.append("| 판정 | ").append(verdict).append(" |\n");
        md.append("| 규칙 수 | ")
                .append(rules.size())
                .append(" (불변식 ")
                .append(invariantCount)
                .append(") |\n");
        md.append("| 총 위반 | ").append(totalViolations).append(" |\n");
        md.append("| 총 소요 | ").append(totalElapsed).append(" ms |\n\n");
    }

    private void appendRuleTable(StringBuilder md, String round, List<Map<String, Object>> rules) {
        md.append("## 규칙별 결과\n\n");
        md.append("| 규칙 | 이름 | 판정 | 검사 행 | 위반 | 샘플 | 소요(ms) |\n");
        md.append("| --- | --- | :--: | ---: | ---: | ---: | ---: |\n");

        for (Map<String, Object> r : rules) {
            md.append("| `")
                    .append(code(r))
                    .append("` | ")
                    .append(r.get("rule_name"))
                    .append(" | ")
                    .append(verdictOf(r, round))
                    .append(" | ")
                    .append(nullSafe(r.get("checked_rows")))
                    .append(" | ")
                    .append(r.get("violation_count"))
                    .append(" | ")
                    .append(r.get("sampled_count"))
                    .append(" | ")
                    .append(nullSafe(r.get("elapsed_ms")))
                    .append(" |\n");
        }

        md.append("\n");
        md.append("> `검사 행` 이 비어 있는 규칙은 전수 스캔이 아니라 존재 검사(NOT EXISTS)로 판정한다.\n");
        md.append("> `N/A` 는 통과가 아니라 **이 회차에서 검사 대상이 아니었다**는 뜻이다.\n\n");
    }

    private void appendViolations(StringBuilder md, String runId) {
        List<Map<String, Object>> violations =
                jdbcTemplate.queryForList(
                        """
                        SELECT rule_code, target_table, target_id, detail
                        FROM verification_violation
                        WHERE run_id = ?
                        ORDER BY rule_code, target_id
                        LIMIT ?
                        """,
                        runId,
                        SAMPLE_LIMIT);

        md.append("## 위반 샘플\n\n");
        if (violations.isEmpty()) {
            md.append("위반 없음.\n");
            return;
        }

        md.append("| 규칙 | 대상 테이블 | 대상 ID | 상세 |\n");
        md.append("| --- | --- | ---: | --- |\n");
        for (Map<String, Object> v : violations) {
            md.append("| `")
                    .append(v.get("rule_code"))
                    .append("` | ")
                    .append(v.get("target_table"))
                    .append(" | ")
                    .append(v.get("target_id"))
                    .append(" | ")
                    .append(nullSafe(v.get("detail")))
                    .append(" |\n");
        }
        md.append("\n최대 ").append(SAMPLE_LIMIT).append("건까지만 표시한다. 전체는 `/violations` 로 조회한다.\n");
    }

    /**
     * CLOCK-02 는 Redis 시계로 발급 시각을 기록하는 회차에서만 판정한다. 그 외 회차에서는 검사하지 않았으므로 통과가 아니라 N/A 다. 이름 문자열이 아니라
     * 저장된 round 값에서 유도한다.
     */
    private String verdictOf(Map<String, Object> rule, String round) {
        if ("CLOCK-02".equals(code(rule)) && !usesRedisClock(round)) {
            return "N/A";
        }
        return passed(rule) ? "통과" : "**위반**";
    }

    private boolean usesRedisClock(String round) {
        if (round == null || round.isBlank()) {
            return false;
        }
        try {
            return RoundVersion.valueOf(round).usesRedisClock();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String describeRound(String round) {
        if (round == null || round.isBlank()) {
            return "기록 없음";
        }
        try {
            RoundVersion v = RoundVersion.valueOf(round);
            return "`" + round + "` (" + v.description() + ")";
        } catch (IllegalArgumentException e) {
            return "`" + round + "` (알 수 없는 값)";
        }
    }

    private static String code(Map<String, Object> rule) {
        return String.valueOf(rule.get("rule_code"));
    }

    private static boolean passed(Map<String, Object> rule) {
        Object p = rule.get("passed");
        return p instanceof Boolean b ? b : ((Number) p).intValue() == 1;
    }

    private static String nullSafe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
