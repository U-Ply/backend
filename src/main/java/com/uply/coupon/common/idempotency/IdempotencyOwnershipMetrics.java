package com.uply.coupon.common.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link IdempotencyChecker#complete}/{@link IdempotencyChecker#release} CAS가 소유권 상실로 실패했을 때
 * 발급/사용·취소/일괄 회수 세 호출부가 공통으로 기록하는 경고 로그·카운터.
 *
 * <p>CAS 실패는 이 요청 실행이 PROCESSING_TTL 안에 처리를 끝내지 못해 다른 요청이 같은 키를 새로 선점했다는 뜻이다. 현재 Redis 값은 이미 다른 요청의
 * 것이므로 건드리지 않고, 여기서는 관측만 한다.
 */
@Slf4j
@Component
public class IdempotencyOwnershipMetrics {

    private final Counter ownerLostCounter;
    private final Counter completeRejectedCounter;
    private final Counter releaseRejectedCounter;

    public IdempotencyOwnershipMetrics(MeterRegistry meterRegistry) {
        this.ownerLostCounter =
                Counter.builder("coupon.idempotency.owner_lost")
                        .description("PROCESSING_TTL 안에 완료·해제하지 못해 다른 요청이 같은 키를 새로 선점한 횟수")
                        .register(meterRegistry);
        this.completeRejectedCounter =
                Counter.builder("coupon.idempotency.complete_rejected")
                        .description("소유권 상실로 COMPLETED 전환(complete) CAS가 거부된 횟수")
                        .register(meterRegistry);
        this.releaseRejectedCounter =
                Counter.builder("coupon.idempotency.release_rejected")
                        .description("소유권 상실로 PROCESSING 해제(release) CAS가 거부된 횟수")
                        .register(meterRegistry);
    }

    public void recordCompleteRejected(String idempotencyKey) {
        completeRejectedCounter.increment();
        ownerLostCounter.increment();
        log.warn(
                "[멱등성] complete CAS가 거부되었습니다(소유권 상실) - 비즈니스 처리는 이미 성립했으므로 결과는 불명확 상태로"
                        + " 남습니다. key: {}",
                idempotencyKey);
    }

    public void recordReleaseRejected(String idempotencyKey) {
        releaseRejectedCounter.increment();
        ownerLostCounter.increment();
        log.warn("[멱등성] release CAS가 거부되었습니다(소유권 상실) - 현재 키는 건드리지 않습니다. key: {}", idempotencyKey);
    }
}
