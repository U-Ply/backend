package com.uply.coupon.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailMaskingConverterTest {

    // 실제 Logback 출력 경계에서 로그 메시지의 이메일이 마스킹되는지 검증합니다.
    @DisplayName("Logback 출력 시 이메일을 마스킹한다")
    @Test
    void masksEmailAtLogbackOutputBoundary() {
        PatternLayout layout = createLayout("%maskEmail(%msg)");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(getClass().getName());
        event.setLevel(Level.INFO);
        event.setMessage("로그인 사용자 email=kim123@example.com");

        assertThat(layout.doLayout(event)).isEqualTo("로그인 사용자 email=kim***@example.com");
    }

    // 예외 메시지에 포함된 이메일도 스택 트레이스 출력 전에 마스킹되는지 검증합니다.
    @DisplayName("Logback 예외 출력 시 이메일을 마스킹한다")
    @Test
    void masksEmailInThrowableAtLogbackOutputBoundary() {
        PatternLayout layout = createLayout("%maskEmail(%msg%n%ex)");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(getClass().getName());
        event.setLevel(Level.ERROR);
        event.setMessage("사용자 처리 실패");
        event.setThrowableProxy(
                new ThrowableProxy(new IllegalStateException("email=kim123@example.com 처리 중 오류")));

        assertThat(layout.doLayout(event))
                .contains("email=kim***@example.com")
                .doesNotContain("kim123@example.com");
    }

    private PatternLayout createLayout(String pattern) {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put("maskEmail", EmailMaskingConverter.class.getName());
        layout.setPattern(pattern);
        layout.start();
        return layout;
    }
}
