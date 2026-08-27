package com.uply.coupon.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponIssueMetricsTest {

    private MeterRegistry meterRegistry;
    private CouponIssueMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new CouponIssueMetrics(meterRegistry);
    }

    private double failureCount(String reasonTag) {
        return meterRegistry.get("coupon.issue.failure").tag("reason", reasonTag).counter().count();
    }

    @Test
    @DisplayName("모든 실패 사유가 0으로 미리 등록된다")
    void registersEveryReasonUpFront() {
        // 대시보드에서 "정상이라 0건"과 "수집이 안 되는 중"을 구분하려면 아직 발생하지 않은
        // 사유도 시계열이 존재해야 한다.
        for (IssueFailReason reason : IssueFailReason.values()) {
            assertThat(failureCount(reason.name().toLowerCase())).isZero();
        }
        assertThat(meterRegistry.get("coupon.issue.success").counter().count()).isZero();
    }

    @Test
    @DisplayName("사유별로 해당 태그의 카운터만 증가한다")
    void incrementsOnlyTheMatchingReason() {
        metrics.failure(IssueFailReason.OUT_OF_STOCK);
        metrics.failure(IssueFailReason.OUT_OF_STOCK);
        metrics.failure(IssueFailReason.ALREADY_ISSUED);

        assertThat(failureCount("out_of_stock")).isEqualTo(2.0);
        assertThat(failureCount("already_issued")).isEqualTo(1.0);
        assertThat(failureCount("campaign_not_cached")).isZero();
    }

    @Test
    @DisplayName("성공 카운터는 실패 카운터와 섞이지 않는다")
    void successIsCountedSeparately() {
        metrics.success();
        metrics.failure(IssueFailReason.OUT_OF_STOCK);

        assertThat(meterRegistry.get("coupon.issue.success").counter().count()).isEqualTo(1.0);
        assertThat(failureCount("out_of_stock")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 레지스트리로 여러 번 만들어도 집계가 한 곳에 모인다")
    void sharesMetersAcrossInstances() {
        // GlobalExceptionHandler는 생성자를 넓히지 않으려고 자체 인스턴스를 만든다.
        // 그래도 집계가 나뉘지 않아야 이 설계가 성립한다.
        CouponIssueMetrics another = new CouponIssueMetrics(meterRegistry);

        metrics.failure(IssueFailReason.OUT_OF_STOCK);
        another.failure(IssueFailReason.OUT_OF_STOCK);
        metrics.success();
        another.success();

        assertThat(failureCount("out_of_stock")).isEqualTo(2.0);
        assertThat(meterRegistry.get("coupon.issue.success").counter().count()).isEqualTo(2.0);
    }
}
