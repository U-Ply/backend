package com.uply.coupon.coupon.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CouponIssueStrategySelector {

    private final Map<String, CouponIssueStrategy> strategies;

    private final String selectedName;

    public CouponIssueStrategySelector(
            List<CouponIssueStrategy> strategies,
            @Value("${coupon.issue.strategy}") String selectedName) {

        this.strategies =
                strategies.stream()
                        .collect(Collectors.toMap(CouponIssueStrategy::name, Function.identity()));
        this.selectedName = selectedName;

        if (!this.strategies.containsKey(selectedName)) {
            throw new IllegalStateException(
                    "알 수 없는 발급 전략: " + selectedName + " (사용 가능: " + this.strategies.keySet() + ")");
        }

        // 어떤 전략 썼는지 로그로 남기기
        log.info("발급 전략 = {} (등록된 전략: {})", selectedName, this.strategies.keySet());
    }

    // 현재 설정된 전략 반환
    public CouponIssueStrategy current() {
        return strategies.get(selectedName);
    }
}
