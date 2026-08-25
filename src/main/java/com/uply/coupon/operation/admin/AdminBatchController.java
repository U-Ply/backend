package com.uply.coupon.operation.admin;

import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/batch")
@RequiredArgsConstructor
public class AdminBatchController {

    /** URL 의 짧은 이름 → Spring Batch Job 빈 이름. URL 에 구현 세부(Job 접미사)가 새어나가지 않게 한 겹 둔다. */
    private static final Map<String, String> JOB_KEYS =
            Map.of(
                    "verification", "verificationJob",
                    "expiration", "expirationJob",
                    "reconcile", "stockReconcileJob");

    private final BatchLaunchService launchService;
    private final JdbcTemplate jdbcTemplate;
    private final VerificationReportRenderer reportRenderer;

    // ─────────────────────── 실행 ───────────────────────

    /** 배치를 접수한다. 완료를 기다리지 않으므로 202 다. 진행 상황은 응답의 jobExecutionId 로 조회한다. */
    @PostMapping("/{jobKey}")
    public ResponseEntity<?> launch(
            @PathVariable String jobKey,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Boolean failOnViolation,
            @RequestParam(required = false) String round)
            throws Exception {

        String jobName = JOB_KEYS.get(jobKey);
        if (jobName == null) {
            throw new BatchInvalidRequestException("알 수 없는 배치: " + jobKey);
        }

        JobExecution execution = launchService.launch(jobName, runId, failOnViolation, round);
        JobParameters params = execution.getJobParameters();
        String assignedRunId = params.getString("runId");
        String assignedRound = params.getString("round");

        log.info(
                "배치 접수 — job={}, runId={}, round={}, executionId={}",
                jobName,
                assignedRunId,
                assignedRound,
                execution.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", jobKey);
        body.put("jobName", jobName);
        body.put("runId", String.valueOf(assignedRunId));
        body.put("round", assignedRound); // 회차 개념이 없는 배치는 null 이 그대로 나간다
        body.put("jobExecutionId", execution.getId());
        body.put("status", execution.getStatus().name());

        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<?> execution(@PathVariable long executionId) {

        JobExecution execution = launchService.findExecution(executionId);
        if (execution == null) {
            throw new BatchExecutionNotFoundException(executionId);
        }

        List<Map<String, Object>> steps =
                execution.getStepExecutions().stream().map(this::toStepSummary).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobName", execution.getJobInstance().getJobName());
        body.put("runId", String.valueOf(execution.getJobParameters().getString("runId")));
        body.put("round", execution.getJobParameters().getString("round"));
        body.put("status", execution.getStatus().name());
        body.put("exitCode", execution.getExitStatus().getExitCode());
        body.put("startTime", String.valueOf(execution.getStartTime()));
        body.put("endTime", String.valueOf(execution.getEndTime()));
        body.put("steps", steps);

        // 실패 원인을 여기서 바로 보여준다. 서버 로그를 뒤지지 않아도 되게.
        body.put(
                "failures",
                execution.getAllFailureExceptions().stream().map(Throwable::getMessage).toList());

        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toStepSummary(StepExecution step) {
        return Map.of(
                "name", step.getStepName(),
                "status", step.getStatus().name(),
                "readCount", step.getReadCount(),
                "writeCount", step.getWriteCount(),
                "commitCount", step.getCommitCount());
    }

    // ─────────────────────── 조회 ───────────────────────

    /**
     * 최근 검증 회차 목록. 회차 하나가 한 줄로 요약된다.
     *
     * <p><b>CASE 순서는 VerificationReportRenderer 의 판정 사슬과 같아야 한다.</b> 같은 회차가 이 API 에서는 BASELINE,
     * 마크다운에서는 불완전으로 불리면 어느 쪽을 믿어야 할지 알 수 없다.
     *
     * <ul>
     *   <li>{@code INVALID} — 시계 규칙(CLOCK-*)이 깨졌다. 어느 시점을 본 것인지 알 수 없으므로 나머지 판정에 의미가 없다.
     *   <li>{@code INCOMPLETE} — 실행되지 않은 규칙이 있다. 검사하지 않은 것을 통과로 세지 않는다.
     *   <li>{@code BASELINE} — V0. 위반 수로 통과·실패를 가르지 않는다 (test-plan 5.4).
     *   <li>{@code FAILED} / {@code PASSED} — 검사한 규칙의 위반 유무.
     * </ul>
     */
    @GetMapping("/verification/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "20") int limit) {
        return jdbcTemplate.queryForList(
                """
                SELECT run_id,
                       MAX(round)                                      AS round,
                       MIN(snapshot_at)                                AS snapshot_at,
                       SUM(violation_count)                            AS total_violations,
                       SUM(status = 'CHECKED' AND violation_count > 0) AS failed_rules,
                       SUM(status = 'CHECKED')                         AS checked_rules,
                       SUM(status = 'NOT_APPLICABLE')                  AS not_applicable_rules,
                       SUM(status = 'SKIPPED')                         AS skipped_rules,
                       COUNT(*)                                        AS rule_count,
                       SUM(elapsed_ms)                                 AS total_elapsed_ms,
                       CASE
                         WHEN SUM(rule_code LIKE 'CLOCK-%' AND violation_count > 0) > 0
                              THEN 'INVALID'
                         WHEN SUM(status = 'SKIPPED') > 0 THEN 'INCOMPLETE'
                         WHEN MAX(round) = 'V0' THEN 'BASELINE'
                         WHEN SUM(status = 'CHECKED' AND violation_count > 0) > 0 THEN 'FAILED'
                         ELSE 'PASSED'
                       END AS verdict
                FROM verification_report
                GROUP BY run_id
                ORDER BY MIN(created_at) DESC
                LIMIT ?
                """,
                limit);
    }

    /**
     * 회차 하나의 규칙별 결과.
     *
     * <p>{@code status} 와 {@code passed} 를 함께 낸다. 기존 화면이 {@code passed} 에 의존하므로 계약을 깨지 않는다.
     *
     * <p><b>{@code passed} 를 SQL 이 아니라 여기서 계산하는 이유.</b> MySQL 의 {@code TRUE}/{@code FALSE} 는 정수 1/0
     * 리터럴이다. {@code CASE ... THEN true END AS passed} 로 쓰면 컬럼 타입이 boolean 이 되지 않아 JDBC 가 Integer 로
     * 받고 JSON 에 숫자 {@code 1}/{@code 0} 으로 나간다. 계약이 {@code true}/{@code false} 이므로 Java 에서 실제
     * Boolean 으로 바꾼다.
     *
     * <p>DB 의 생성 컬럼 {@code passed} 는 쓰지 않는다. 그 컬럼은 {@code violation_count = 0} 으로만 계산되므로 아무것도 검사하지
     * 않은 NOT_APPLICABLE·SKIPPED 규칙까지 통과로 잡힌다.
     */
    @GetMapping("/verification/runs/{runId}")
    public List<Map<String, Object>> report(@PathVariable String runId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        """
                        SELECT rule_code,
                               rule_name,
                               round,
                               status,
                               violation_count,
                               sampled_count,
                               checked_rows,
                               elapsed_ms,
                               snapshot_at
                        FROM verification_report
                        WHERE run_id = ?
                        ORDER BY rule_code
                        """,
                        runId);

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 조회 결과를 그대로 고치지 않고 새 맵에 담는다. 불변 맵이 올라와도 깨지지 않는다.
            Map<String, Object> rule = new LinkedHashMap<>(row);
            rule.put("passed", isPassed(row));
            result.add(rule);
        }
        return result;
    }

    /** 검사했고(CHECKED) 위반이 없을 때만 통과다. N/A·미실행은 통과가 아니다. */
    private static boolean isPassed(Map<String, Object> rule) {
        if (!"CHECKED".equals(rule.get("status"))) {
            return false;
        }
        Object violations = rule.get("violation_count");
        return violations != null && ((Number) violations).longValue() == 0L;
    }

    /** 회차 하나의 검증 결과를 마크다운으로 낸다. acceptance 스크립트가 파일로 떨군다. */
    @GetMapping(
            value = "/verification/runs/{runId}/report",
            produces = "text/markdown; charset=UTF-8")
    public ResponseEntity<String> reportMarkdown(@PathVariable String runId) {
        return ResponseEntity.ok(reportRenderer.render(runId));
    }

    /** 위반 샘플. ruleCode 로 좁힐 수 있다. */
    @GetMapping("/verification/runs/{runId}/violations")
    public List<Map<String, Object>> violations(
            @PathVariable String runId,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(defaultValue = "100") int limit) {

        if (ruleCode == null || ruleCode.isBlank()) {
            return jdbcTemplate.queryForList(
                    """
                    SELECT rule_code, target_table, target_id, detail
                    FROM verification_violation
                    WHERE run_id = ?
                    ORDER BY rule_code, target_id
                    LIMIT ?
                    """,
                    runId,
                    limit);
        }

        return jdbcTemplate.queryForList(
                """
                SELECT rule_code, target_table, target_id, detail
                FROM verification_violation
                WHERE run_id = ? AND rule_code = ?
                ORDER BY target_id
                LIMIT ?
                """,
                runId,
                ruleCode,
                limit);
    }
}
