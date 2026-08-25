package com.uply.coupon.common.privacy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/** 로그 메시지와 예외 문자열이 출력되기 직전에 이메일을 마스킹하는 Logback 변환기. */
public class EmailMaskingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String output) {
        return PrivacyMasker.maskEmails(output);
    }
}
