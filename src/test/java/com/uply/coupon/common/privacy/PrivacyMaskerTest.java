package com.uply.coupon.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PrivacyMaskerTest {

    // 정상 이메일은 로컬 파트 앞 세 글자만 남기고 나머지를 마스킹하는지 검증합니다.
    @DisplayName("이메일의 로컬 파트는 앞 세 글자만 남기고 마스킹한다")
    @ParameterizedTest
    @CsvSource({
        "kim123@example.com, kim***@example.com",
        "kim@example.com, kim***@example.com",
        "ab@example.com, ab***@example.com",
        "a@example.com, a***@example.com"
    })
    void masksEmailLocalPart(String email, String expected) {
        assertThat(PrivacyMasker.maskEmail(email)).isEqualTo(expected);
    }

    // null,빈 문자열,공백 입력은 원문 없이 전체 마스킹하는지 검증합니다.
    @DisplayName("null 또는 빈 이메일은 원문 없이 전체 마스킹한다")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void masksNullOrBlankEmail(String email) {
        assertThat(PrivacyMasker.maskEmail(email)).isEqualTo("***");
    }

    // 잘못된 이메일 형식은 일부 값도 노출하지 않고 전체 마스킹하는지 검증합니다.
    @DisplayName("형식이 올바르지 않은 이메일은 원문 없이 전체 마스킹한다")
    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-an-email",
                "@example.com",
                "kim@",
                "kim@@example.com",
                "kim 123@example.com",
                "kim@example .com"
            })
    void fullyMasksInvalidEmail(String email) {
        assertThat(PrivacyMasker.maskEmail(email)).isEqualTo("***");
    }
}
