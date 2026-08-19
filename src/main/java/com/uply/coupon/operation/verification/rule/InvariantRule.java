package com.uply.coupon.operation.verification.rule;

/** 항상 참이어야 하는 명제 하나 */
public interface InvariantRule {

    /** "INV-01" 접두어 */
    String code();

    /** "초과 발급 금지" — 사람이 읽는 이름 */
    String name();

    /** 위반 행을 반환하는 SQL */
    String violationSql();

    /** 이 규칙이 검사한 행 수 */
    default String checkedRowsSql() {
        return null;
    }
}
