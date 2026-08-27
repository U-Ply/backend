package com.uply.coupon.common.metrics;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 발급 요청의 성공·실패 건수를 사유별로 집계한다.
 *
 * <p>요구분석서 15.3절이 관측 항목으로 "발급 성공, 재고 소진, 중복 발급 및 보상 발생 건수"를 요구한다. 보상은 {@code
 * coupon.redis.compensation}이 이미 담당하므로 나머지 셋을 여기서 담당한다.
 *
 * <p>실패는 사유마다 이름을 나누지 않고 {@code coupon.issue.failure} 하나에 {@code reason} 태그로만 가른다. 이름을 쪼개면 전체 실패율을
 * 구할 때마다 대시보드가 이름을 모두 나열해야 하고, 사유가 늘 때마다 쿼리를 고쳐야 한다.
 *
 * <p>Micrometer는 이름과 태그가 같으면 같은 미터를 돌려준다. 그래서 이 객체를 여러 곳에서 따로 만들어도 집계는 한 곳에 모인다.
 */
@Component
public class CouponIssueMetrics {

    private static final String SUCCESS_METER = "coupon.issue.success";
    private static final String FAILURE_METER = "coupon.issue.failure";

    private final Counter successCounter;

    private final Map<IssueFailReason, Counter> failureCounters =
            new EnumMap<>(IssueFailReason.class);

    public CouponIssueMetrics(MeterRegistry meterRegistry) {
        this.successCounter =
                Counter.builder(SUCCESS_METER)
                        .description("쿠폰 발급이 실제로 성립한 횟수 (멱등성 캐시 재응답 제외)")
                        .register(meterRegistry);

        // 사유를 미리 전부 등록해 둔다. 처음 발생할 때 등록하면 아직 한 번도 나지 않은 사유가
        // 대시보드에서 0이 아니라 빈 시계열로 보여, "정상이라 0건"과 "수집이 안 되는 중"을
        // 구분할 수 없다.
        for (IssueFailReason reason : IssueFailReason.values()) {
            failureCounters.put(
                    reason,
                    Counter.builder(FAILURE_METER)
                            .tag("reason", tagOf(reason))
                            .description("발급 요청이 사유별로 실패한 횟수")
                            .register(meterRegistry));
        }
    }

    /**
     * 발급이 실제로 성립한 순간에만 호출한다.
     *
     * <p>멱등성 캐시 히트로 이전 응답을 그대로 돌려주는 경로에서는 호출하면 안 된다. 그 경로는 쿠폰을 새로 만들지 않으므로, 세면 성공 건수가 실제 발급 수보다 부풀어
     * 부하 테스트의 "성공 + 재고소진 = 총 요청" 검산이 깨진다.
     */
    public void success() {
        successCounter.increment();
    }

    /** 알 수 없는 사유가 들어오면 조용히 무시한다. 지표 집계가 발급 응답을 망가뜨리면 안 된다. */
    public void failure(IssueFailReason reason) {
        Counter counter = failureCounters.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    private static String tagOf(IssueFailReason reason) {
        return reason.name().toLowerCase(Locale.ROOT);
    }
}
