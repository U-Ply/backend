package com.uply.coupon.operation.verification.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 이 회차가 어느 구현 버전의 산출물인지.
 *
 * <p>발급 전략 이름(NO_LOCK / PESSIMISTIC_LOCK / LUA_SCRIPT)만으로는 시계를 가릴 수 없다. V2 와 V3 는 둘 다 LUA_SCRIPT 지만
 * issued_at 을 찍는 시계가 다르다.
 *
 * <p><b>전략 쌍을 여기에 둔 이유.</b> {@code round} 는 URL 파라미터로 들어오고 실제 전략은 애플리케이션 설정에서 온다. 두 값이 모순돼도 아무도
 * 확인하지 않으면, 앱이 V1 설정으로 떠 있는데 리포트에는 {@code round=V3 PASSED} 가 남는다. 실제로 {@code BULK-02} 회차가 그렇게
 * 기록됐다(bulk-verification.md 관찰 1). 회차 라벨과 실행 설정의 대응을 코드 한 곳에 두고 {@link #matches(String, String)} 로
 * 강제한다.
 */
public enum RoundVersion {
    V0("NoLock", false, "NO_LOCK", "sync-db"),
    V1("PessimisticLock", false, "PESSIMISTIC_LOCK", "sync-db"),
    // Lua 가 Redis TIME 으로 찍은 nowMillis 가 issued_at 과 event_at 에 그대로 들어간다.
    // (SyncMysqlSaveStrategy / CouponIssuedPersistenceService 둘 다 issuedAt 을 넘긴다)
    // 실측 drift ±0.09s 이내, 허용치 0.3s — application.yml 참고.
    V2("Lua + MySQL 동기 저장", true, "LUA_SCRIPT", "sync-db"),
    V3("Lua + Kafka", true, "LUA_SCRIPT", "kafka");

    private final String description;
    private final boolean usesRedisClock;
    private final String issueStrategy;
    private final String saveStrategy;

    RoundVersion(
            String description, boolean usesRedisClock, String issueStrategy, String saveStrategy) {
        this.description = description;
        this.usesRedisClock = usesRedisClock;
        this.issueStrategy = issueStrategy;
        this.saveStrategy = saveStrategy;
    }

    public String description() {
        return description;
    }

    /** issued_at / event_at 을 Redis 시계로 기록하는 경로인가. CLOCK-02 판정 여부를 가른다. */
    public boolean usesRedisClock() {
        return usesRedisClock;
    }

    /** 이 회차가 요구하는 {@code coupon.issue.strategy} 값. */
    public String issueStrategy() {
        return issueStrategy;
    }

    /** 이 회차가 요구하는 {@code coupon.save.strategy} 값. */
    public String saveStrategy() {
        return saveStrategy;
    }

    /**
     * 실행 중인 애플리케이션 설정이 이 회차와 일치하는가.
     *
     * <p>null 이나 빈 값은 일치로 보지 않는다. 설정을 읽지 못한 것을 "맞다" 로 처리하면 검사 자체가 사라진다.
     */
    public boolean matches(String actualIssueStrategy, String actualSaveStrategy) {
        return issueStrategy.equalsIgnoreCase(normalize(actualIssueStrategy))
                && saveStrategy.equalsIgnoreCase(normalize(actualSaveStrategy));
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 알 수 없는 값을 조용히 넘기지 않는다. 오타가 곧 검사 누락이 되기 때문이다. */
    public static RoundVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("round is required: " + Arrays.toString(values()));
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown round: " + raw + " (allowed: " + Arrays.toString(values()) + ")");
        }
    }
}
