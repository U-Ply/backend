package com.uply.coupon.operation.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;

/**
 * 회차 라벨과 실행 전략의 불일치를 막는 가드.
 *
 * <p>{@code round} 는 요청자가 URL 로 지정하고 실제 전략은 애플리케이션 설정에서 온다. 둘이 모순돼도 아무도 확인하지 않으면 잘못된 라벨이 리포트에 남는다.
 * 실제로 {@code BULK-02} 가 그렇게 기록됐다.
 */
class BatchLaunchServiceTest {

    private static final String VERIFICATION_JOB = "verificationJob";
    private static final String EXPIRATION_JOB = "expirationJob";

    private BatchLaunchService service(String issueStrategy, String saveStrategy) throws Exception {
        JobExplorer explorer = mock(JobExplorer.class);
        given(explorer.findRunningJobExecutions(anyString())).willReturn(Set.of());

        JobRepository repository = mock(JobRepository.class);
        given(repository.createJobExecution(anyString(), any(JobParameters.class)))
                .willReturn(new JobExecution(1L));

        Map<String, Job> jobs =
                Map.of(
                        VERIFICATION_JOB, jobMock(VERIFICATION_JOB),
                        EXPIRATION_JOB, jobMock(EXPIRATION_JOB));

        return new BatchLaunchService(jobs, explorer, repository, issueStrategy, saveStrategy);
    }

    private Job jobMock(String name) {
        Job job = mock(Job.class);
        given(job.getName()).willReturn(name);
        given(job.getJobParametersValidator()).willReturn(new DefaultJobParametersValidator());
        return job;
    }

    @Test
    @DisplayName("회차와 실행 전략이 일치하면 그대로 띄운다.")
    void launch_RoundMatchesStrategy_Launches() throws Exception {
        BatchLaunchService service = service("PESSIMISTIC_LOCK", "sync-db");

        assertThatCode(() -> service.launch(VERIFICATION_JOB, "L2-V1-01", false, "V1"))
                .doesNotThrowAnyException();
    }

    /**
     * BULK-02 의 재현. 앱은 PESSIMISTIC_LOCK 으로 떠 있는데 round=V2 로 기록됐고 판정은 PASSED 였다. 잘못 붙은 라벨은 나중에 구분할
     * 방법이 없으므로 만들지 않는 편이 싸다.
     */
    @Test
    @DisplayName("V2 라벨인데 앱이 비관적 락으로 떠 있으면 400 으로 거부한다.")
    void launch_IssueStrategyMismatch_Rejected() throws Exception {
        BatchLaunchService service = service("PESSIMISTIC_LOCK", "sync-db");

        assertThatThrownBy(() -> service.launch(VERIFICATION_JOB, "BULK-02", false, "V2"))
                .isInstanceOf(BatchInvalidRequestException.class)
                .hasMessageContaining("LUA_SCRIPT")
                .hasMessageContaining("PESSIMISTIC_LOCK");
    }

    /** V2 와 V3 는 발급 전략이 같다. 저장 전략까지 봐야 갈린다. */
    @Test
    @DisplayName("발급 전략이 같아도 저장 전략이 다르면 거부한다.")
    void launch_SaveStrategyMismatch_Rejected() throws Exception {
        BatchLaunchService service = service("LUA_SCRIPT", "sync-db");

        assertThatThrownBy(() -> service.launch(VERIFICATION_JOB, "L2-V3-01", false, "V3"))
                .isInstanceOf(BatchInvalidRequestException.class)
                .hasMessageContaining("kafka");
    }

    @Test
    @DisplayName("round 가 없으면 여전히 400 이다.")
    void launch_RoundMissing_Rejected() throws Exception {
        BatchLaunchService service = service("PESSIMISTIC_LOCK", "sync-db");

        assertThatThrownBy(() -> service.launch(VERIFICATION_JOB, "L2-V1-01", false, null))
                .isInstanceOf(BatchInvalidRequestException.class)
                .hasMessageContaining("round is required");
    }

    /** 만료 배치는 회차 개념이 없다. 가드가 다른 Job 까지 막으면 안 된다. */
    @Test
    @DisplayName("만료 배치는 round 없이도 띄운다.")
    void launch_ExpirationJob_NotAffected() throws Exception {
        BatchLaunchService service = service("LUA_SCRIPT", "kafka");

        assertThatCode(() -> service.launch(EXPIRATION_JOB, null, null, null))
                .doesNotThrowAnyException();
    }
}
