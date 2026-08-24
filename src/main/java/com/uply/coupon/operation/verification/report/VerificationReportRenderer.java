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
                               SELECT round, status, rule_code, rule_name, snapshot_at, violation_count,
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
        appendRuleTable(md, rules);
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
        long failedInvariants =
                rules.stream()
                        .filter(r -> code(r).startsWith("INV-"))
                        .filter(r -> !passed(r))
                        .count();
        // REC-01 은 DB 자체가 깨진 것이 아니라 Redis 와 어긋난 것이다. 등급은 나누되
        // 통과로 뭉개지 않는다 — 판정 한 줄만 읽는 사람에게 거짓말을 하지 않기 위해서다.
        long failedReconciliation =
                rules.stream()
                        .filter(r -> code(r).startsWith("REC-"))
                        .filter(r -> !passed(r))
                        .count();
        // 새 규칙 코드가 늘었을 때 어느 분류에도 안 걸려 조용히 통과되는 것을 막는다.
        long failedOther =
                rules.stream()
                        .filter(r -> !code(r).startsWith("INV-"))
                        .filter(r -> !code(r).startsWith("REC-"))
                        .filter(r -> !code(r).startsWith("CLOCK-"))
                        .filter(r -> !passed(r))
                        .count();
        boolean clockValid =
                rules.stream()
                        .filter(r -> code(r).startsWith("CLOCK-"))
                        .allMatch(VerificationReportRenderer::passed);
        long skipped = rules.stream().filter(r -> "SKIPPED".equals(status(r))).count();
        long checkedCount = rules.stream().filter(r -> "CHECKED".equals(status(r))).count();
        long naCount = rules.stream().filter(r -> "NOT_APPLICABLE".equals(status(r))).count();
        String verdict;
        if (!clockValid) {
            verdict = "**무효** — 시계가 어긋나 어느 시점을 본 것인지 알 수 없다";
        } else if (skipped > 0) {
            verdict = "**불완전** — 규칙 " + skipped + "개가 전제 조건 미충족으로 실행되지 않았다";
        } else if ("V0".equals(round)) {
            verdict = "**BASELINE** — NoLock 동시성 문제 재현 결과";
        } else if (failedInvariants > 0) {
            verdict = "**실패** — 불변식 " + failedInvariants + "개 위반";
        } else if (failedOther > 0) {
            verdict = "**실패** — 분류되지 않은 규칙 " + failedOther + "개 위반";
        } else if (failedReconciliation > 0) {
            verdict = "**불일치** — Redis·DB 재고 " + failedReconciliation + "건 어긋남 (DB 자체는 정합)";
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
                .append(" (검사 ")
                .append(checkedCount)
                .append(" / N/A ")
                .append(naCount)
                .append(" / 미실행 ")
                .append(skipped)
                .append(") |\n");
        md.append("| 총 위반 | ").append(totalViolations).append(" |\n");
        md.append("| 총 소요 | ").append(totalElapsed).append(" ms |\n\n");
    }

    private void appendRuleTable(StringBuilder md, List<Map<String, Object>> rules) {
        md.append("## 규칙별 결과\n\n");
        md.append("| 규칙 | 이름 | 판정 | 검사 행 | 위반 | 샘플 | 소요(ms) |\n");
        md.append("| --- | --- | :--: | ---: | ---: | ---: | ---: |\n");

        for (Map<String, Object> r : rules) {
            md.append("| `")
                    .append(code(r))
                    .append("` | ")
                    .append(r.get("rule_name"))
                    .append(" | ")
                    .append(verdictOf(r))
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
        md.append("> `N/A` 는 이 회차에 해당하지 않는 규칙이다. 통과가 아니다.\n");
        md.append("> `미실행` 은 검사해야 했지만 전제 조건이 맞지 않아 실행하지 못한 규칙이다.\n");
        md.append("> 이 경우 회차 전체를 통과로 볼 수 없다.\n\n");
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

    private String verdictOf(Map<String, Object> rule) {
        return switch (status(rule)) {
            case "NOT_APPLICABLE" -> "N/A";
            case "SKIPPED" -> "**미실행**";
            default -> passed(rule) ? "통과" : "**위반**";
        };
    }

    private static String status(Map<String, Object> rule) {
        return String.valueOf(rule.get("status"));
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
