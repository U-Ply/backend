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
                               sampled_count, checked_rows, elapsed_ms
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
                        .filter(VerificationReportRenderer::violated)
                        .count();
        // REC-01 은 DB 자체가 깨진 것이 아니라 Redis 와 어긋난 것이다. 등급은 나누되
        // 통과로 뭉개지 않는다 — 판정 한 줄만 읽는 사람에게 거짓말을 하지 않기 위해서다.
        long failedReconciliation =
                rules.stream()
                        .filter(r -> code(r).startsWith("REC-"))
                        .filter(VerificationReportRenderer::violated)
                        .count();
        // 새 규칙 코드가 늘었을 때 어느 분류에도 안 걸려 조용히 통과되는 것을 막는다.
        long failedOther =
                rules.stream()
                        .filter(r -> !code(r).startsWith("INV-"))
                        .filter(r -> !code(r).startsWith("REC-"))
                        .filter(r -> !code(r).startsWith("CLOCK-"))
                        .filter(VerificationReportRenderer::violated)
                        .count();
        boolean clockValid =
                rules.stream()
                        .filter(r -> code(r).startsWith("CLOCK-"))
                        .noneMatch(VerificationReportRenderer::violated);
        long skipped = rules.stream().filter(r -> "SKIPPED".equals(status(r))).count();
        long checkedCount = rules.stream().filter(r -> "CHECKED".equals(status(r))).count();
        long naCount = rules.stream().filter(r -> "NOT_APPLICABLE".equals(status(r))).count();
        /*
         * 판정 사슬의 순서에는 뜻이 있다.
         *
         * 무효·불완전이 BASELINE 보다 앞이다. 이 둘은 "이 회차가 정합했는가" 가 아니라
         * "이 회차를 읽을 수 있는가" 를 말한다. 시계가 어긋났거나 규칙이 빠진 V0 는
         * 기준선으로 쓸 수 없다. 5.4 가 V0 에 요구하는 것도 "위반이 없을 것" 이 아니라
         * "실제 측정값을 기록할 것" 이므로, 기록이 온전하지 않으면 BASELINE 이라고
         * 부를 수 없다.
         *
         * BASELINE 은 그다음이다. V0 는 위반 수로 통과·실패를 가르지 않는다 (5.4).
         *
         * AdminBatchController.runs() 의 SQL CASE 도 같은 순서를 쓴다.
         * 같은 회차가 API 와 마크다운에서 다른 이름으로 불리면 안 된다.
         */
        String verdict;
        if (!clockValid) {
            verdict = "**무효** — 시계가 어긋나 어느 시점을 본 것인지 알 수 없다";
        } else if (skipped > 0 || rules.size() < 15) {
            verdict = "**불완전** — 일부 규칙이 기록되지 않았거나 실행되지 않았다";
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
            default -> violated(rule) ? "**위반**" : "통과";
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

    /**
     * 이 규칙이 실제로 위반됐는가.
     *
     * <p>DB 의 생성 컬럼 {@code passed} 를 쓰지 않는다. 그 컬럼은 {@code violation_count = 0} 으로만 계산되므로 SKIPPED 와
     * NOT_APPLICABLE 까지 통과로 잡힌다. 그 값을 그대로 세면 "검사하지 않은 것을 통과로 기록" 하게 되고, 지금까지 그것을 막고 있던 것은 판정 사슬에서
     * 미실행 분기가 앞에 있다는 사실뿐이었다. 순서가 안전장치를 대신하면 안 되므로 여기서 직접 판정한다. s
     *
     * <p>검사한(CHECKED) 규칙에서 위반이 나온 경우에만 위반이다.
     */
    private static boolean violated(Map<String, Object> rule) {
        if (!"CHECKED".equals(status(rule))) {
            return false;
        }
        Object count = rule.get("violation_count");
        return count != null && ((Number) count).longValue() > 0L;
    }

    private static String nullSafe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
