package com.uply.coupon.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 발급 API 앞단 게이트(레이트 리미터)의 통과·거부 건수.
 *
 * <p>{@code coupon.gate.passed} 는 토큰을 얻어 컨트롤러로 넘어간 요청, {@code coupon.gate.rejected} 는 토큰이 없어 429로
 * 되돌린 요청이다. 인앱 모니터링의 "유입" 단계가 이 둘의 rate 를 읽어 게이트가 스파이크를 얼마나 눌렀는지 보여준다.
 *
 * <p>{@link com.uply.coupon.common.metrics.CouponIssueMetrics} 와 같은 패턴 — 같은 이름의 Counter 를 여러 곳에서
 * 만들어도 Micrometer 가 하나로 모은다.
 */
@Component
public class GateMetrics {

    private final Counter passedCounter;
    private final Counter rejectedCounter;

    public GateMetrics(MeterRegistry meterRegistry) {
        this.passedCounter =
                Counter.builder("coupon.gate.passed")
                        .description("게이트를 통과해 발급 처리로 넘어간 요청 수")
                        .register(meterRegistry);
        this.rejectedCounter =
                Counter.builder("coupon.gate.rejected")
                        .description("게이트에서 토큰 부족으로 429 거부된 요청 수")
                        .register(meterRegistry);
    }

    public void passed() {
        passedCounter.increment();
    }

    public void rejected() {
        rejectedCounter.increment();
    }
}
