package com.uply.coupon.operation.admin;

/** 요청한 runId 로 저장된 검증 결과가 없다. */
public class VerificationRunNotFoundException extends RuntimeException {

    public VerificationRunNotFoundException(String runId) {
        super("검증 회차를 찾을 수 없습니다: runId=" + runId);
    }
}
