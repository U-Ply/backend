package com.uply.coupon.operation.verification.domain;

/** 규칙이 이 회차에서 어떤 상태로 끝났는지. */
public enum RuleStatus {

    /** 실제로 검사했다. 통과 여부는 위반 수가 결정한다. */
    CHECKED,

    /** 이 회차에 해당하지 않는 규칙이다. 정상이며 회차 판정에 영향을 주지 않는다. */
    NOT_APPLICABLE,

    /** 검사해야 하지만 전제 조건이 충족되지 않아 실행하지 못했다. 회차 결론이 불완전하다. */
    SKIPPED
}
