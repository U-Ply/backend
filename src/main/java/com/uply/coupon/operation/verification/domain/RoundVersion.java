package com.uply.coupon.operation.verification.domain;

import java.util.Arrays;

/**
 * 이 회차가 어느 구현 버전의 산출물인지.
 *
 * <p>발급 전략 이름(NO_LOCK / PESSIMISTIC_LOCK / LUA_SCRIPT)만으로는 시계를 가릴 수 없다. V2 와 V3 는 둘 다 LUA_SCRIPT 지만
 * issued_at 을 찍는 시계가 다르다.
 */
public enum RoundVersion {
    V0("NoLock", false),
    V1("PessimisticLock", false),
    // issued_at 이 아직 JVM 시계다. Redis TIME 통일이 확정되면 true 로 바꾼다.
    V2("Lua + MySQL 동기 저장", false),
    V3("Lua + Kafka", false);

    private final String description;
    private final boolean usesRedisClock;

    RoundVersion(String description, boolean usesRedisClock) {
        this.description = description;
        this.usesRedisClock = usesRedisClock;
    }

    public String description() {
        return description;
    }

    /** issued_at / event_at 을 Redis 시계로 기록하는 경로인가. CLOCK-02 판정 여부를 가른다. */
    public boolean usesRedisClock() {
        return usesRedisClock;
    }

    /** 알 수 없는 값을 조용히 넘기지 않는다. 오타가 곧 검사 누락이 되기 때문이다. */
    public static RoundVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("round is required: " + Arrays.toString(values()));
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown round: " + raw + " (allowed: " + Arrays.toString(values()) + ")");
        }
    }
}
