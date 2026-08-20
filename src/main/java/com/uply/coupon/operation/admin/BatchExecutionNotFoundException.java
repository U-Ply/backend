package com.uply.coupon.operation.admin;

public class BatchExecutionNotFoundException extends RuntimeException {

    public BatchExecutionNotFoundException(long executionId) {
        super("배치 실행 이력을 찾을 수 없습니다: executionId=" + executionId);
    }
}
