package com.uply.coupon.operation.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoundVersionTest {

    @Test
    @DisplayName("각 회차는 정해진 전략 쌍하고만 일치한다.")
    void matches_ExactPair_True() {
        assertThat(RoundVersion.V0.matches("NO_LOCK", "sync-db")).isTrue();
        assertThat(RoundVersion.V1.matches("PESSIMISTIC_LOCK", "sync-db")).isTrue();
        assertThat(RoundVersion.V2.matches("LUA_SCRIPT", "sync-db")).isTrue();
        assertThat(RoundVersion.V3.matches("LUA_SCRIPT", "kafka")).isTrue();
    }

    /** V2 와 V3 는 발급 전략이 같다. 저장 전략을 보지 않으면 둘을 구분할 수 없다. */
    @Test
    @DisplayName("V2 와 V3 는 저장 전략으로만 갈린다.")
    void matches_SameIssueStrategyDifferentSave_False() {
        assertThat(RoundVersion.V2.matches("LUA_SCRIPT", "kafka")).isFalse();
        assertThat(RoundVersion.V3.matches("LUA_SCRIPT", "sync-db")).isFalse();
    }

    /** 설정을 읽지 못한 것을 "맞다" 로 처리하면 검사 자체가 사라진다. */
    @Test
    @DisplayName("설정이 비어 있으면 일치로 보지 않는다.")
    void matches_NullOrBlank_False() {
        assertThat(RoundVersion.V1.matches(null, "sync-db")).isFalse();
        assertThat(RoundVersion.V1.matches("PESSIMISTIC_LOCK", null)).isFalse();
        assertThat(RoundVersion.V1.matches("", "")).isFalse();
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백은 무시한다.")
    void matches_CaseAndWhitespaceInsensitive() {
        assertThat(RoundVersion.V3.matches("  lua_script ", " KAFKA ")).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 round 는 조용히 넘어가지 않는다.")
    void parse_Unknown_Throws() {
        assertThatThrownBy(() -> RoundVersion.parse("V9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown round");

        assertThatThrownBy(() -> RoundVersion.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("round is required");
    }
}
