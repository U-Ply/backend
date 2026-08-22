package com.uply.coupon.operation.admin;

import com.uply.coupon.operation.verification.report.VerificationReportRenderer;
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

    /** 최근 검증 회차 목록. 회차 하나가 한 줄로 요약된다. */
    @GetMapping("/verification/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "20") int limit) {
        return jdbcTemplate.queryForList(
                """
                SELECT run_id,
                       MIN(snapshot_at)                            AS snapshot_at,
                       SUM(violation_count)                        AS total_violations,
                       SUM(CASE WHEN passed = 0 THEN 1 ELSE 0 END) AS failed_rules,
                       COUNT(*)                                    AS rule_count,
                       SUM(elapsed_ms)                             AS total_elapsed_ms
                FROM verification_report
                GROUP BY run_id
                ORDER BY MIN(created_at) DESC
                LIMIT ?
                """,
                limit);
    }

    /** 회차 하나의 규칙별 결과. */
    @GetMapping("/verification/runs/{runId}")
    public List<Map<String, Object>> report(@PathVariable String runId) {
        return jdbcTemplate.queryForList(
                """
                SELECT rule_code, rule_name, violation_count, sampled_count,
                       checked_rows, elapsed_ms, passed, snapshot_at
                FROM verification_report
                WHERE run_id = ?
                ORDER BY rule_code
                """,
                runId);
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
