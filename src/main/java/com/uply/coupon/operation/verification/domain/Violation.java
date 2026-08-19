package com.uply.coupon.operation.verification.domain;

public record Violation(String targetTable, long targetId, String detail) {}
