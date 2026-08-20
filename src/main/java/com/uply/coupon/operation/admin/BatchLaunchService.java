package com.uply.coupon.operation.admin;

import com.uply.coupon.operation.verification.domain.RoundVersion;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 관리자 API 에서 배치 Job 을 띄운다.
 *
 * <p>만료 배치는 10만 건에 12초가 걸렸고 300만 건이면 분 단위다. 동기로 띄우면 HTTP 요청이 그동안 물려 있다가 게이트웨이 타임아웃으로 끊긴다. 접수만 하고
 * 202 를 돌려준 뒤, 진행 상황은 executions/{id} 로 확인한다.
 */
@Service
public class BatchLaunchService {

    /** 노출을 허용한 Job 이름. */
    private static final Set<String> ALLOWED_JOBS =
            Set.of("verificationJob", "expirationJob", "stockReconcileJob");

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final Map<String, Job> jobs;
    private final JobExplorer jobExplorer;
    private final TaskExecutorJobLauncher launcher;

    /** Spring 이 Map&lt;String, Job&gt; 에 빈 이름 → 빈 을 채워준다. 빈 이름이 곧 Job 이름이다. */
    public BatchLaunchService(
            Map<String, Job> jobs, JobExplorer jobExplorer, JobRepository jobRepository)
            throws Exception {

        this.jobs = jobs;
        this.jobExplorer = jobExplorer;

        TaskExecutorJobLauncher taskExecutorJobLauncher = new TaskExecutorJobLauncher();
        taskExecutorJobLauncher.setJobRepository(jobRepository);
        taskExecutorJobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("admin-batch-"));
        taskExecutorJobLauncher.afterPropertiesSet();
        this.launcher = taskExecutorJobLauncher;
    }

    /**
     * @param failOnViolation null 이면 Job 기본값(실패시킴)을 따른다. NoLock(V0) 회차처럼 정합성 위반이 의도된 결과인 경우에만 false
     *     를 넘긴다. 위반 내용은 어느 쪽이든 verification_report 에 동일하게 기록된다.
     */
    public JobExecution launch(
            String jobName, String requestedRunId, Boolean failOnViolation, String round)
            throws Exception {

        if (!ALLOWED_JOBS.contains(jobName)) {
            throw new BatchInvalidRequestException("알 수 없는 Job: " + jobName);
        }
        if (!jobs.containsKey(jobName)) {
            throw new BatchNotImplementedException(jobName + " 은 아직 구현되지 않았다.");
        }
        if (!jobExplorer.findRunningJobExecutions(jobName).isEmpty()) {
            throw new BatchConflictException(jobName + " 이 이미 실행 중이다.");
        }

        // 검증 회차는 round 가 필수다. 없으면 CLOCK-02 가 조용히 N/A 로 넘어간다.
        // Tasklet 에서도 parse 하지만, Job 실행이 비동기라 여기서 막아야 400 이 나간다.
        if ("verificationJob".equals(jobName)) {
            try {
                RoundVersion.parse(round);
            } catch (IllegalArgumentException e) {
                throw new BatchInvalidRequestException(e.getMessage());
            }
        }

        String runId =
                (requestedRunId == null || requestedRunId.isBlank())
                        ? LocalDateTime.now().format(RUN_ID_FORMAT)
                        : requestedRunId;

        JobParametersBuilder builder = new JobParametersBuilder().addString("runId", runId);

        if (failOnViolation != null) {
            builder.addString("failOnViolation", failOnViolation.toString(), false);
        }
        if (round != null && !round.isBlank()) {
            builder.addString("round", round.trim().toUpperCase(), false);
        }

        return launcher.run(jobs.get(jobName), builder.toJobParameters());
    }

    public JobExecution findExecution(long executionId) {
        return jobExplorer.getJobExecution(executionId);
    }
}
