package com.uply.coupon.common.privacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 로그에 개인정보 원문이 노출되지 않도록 변환하는 유틸리티. */
public final class PrivacyMasker {

    private static final String FULLY_MASKED = "***";
    private static final Pattern EMAIL_IN_TEXT =
            Pattern.compile(
                    "(?i)(?<![\\p{L}\\p{N}._%+-])"
                            + "[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}"
                            + "(?![\\p{L}\\p{N}._%+-])");

    private PrivacyMasker() {}

    /**
     * 이메일의 로컬 파트는 앞 세 글자까지만 남기고 나머지를 마스킹한다.
     *
     * <p>형식이 올바르지 않은 값은 원문 일부도 노출하지 않는다.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return FULLY_MASKED;
        }

        int atIndex = email.indexOf('@');
        if (atIndex <= 0
                || atIndex != email.lastIndexOf('@')
                || atIndex == email.length() - 1
                || email.chars().anyMatch(Character::isWhitespace)) {
            return FULLY_MASKED;
        }

        String localPart = email.substring(0, atIndex);
        String domainPart = email.substring(atIndex);
        int visibleLength = Math.min(3, localPart.length());

        return localPart.substring(0, visibleLength) + FULLY_MASKED + domainPart;
    }

    /** 로그 메시지 안에 포함된 모든 이메일을 찾아 마스킹한다. */
    public static String maskEmails(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        Matcher matcher = EMAIL_IN_TEXT.matcher(message);
        StringBuilder maskedMessage = new StringBuilder();

        while (matcher.find()) {
            matcher.appendReplacement(
                    maskedMessage, Matcher.quoteReplacement(maskEmail(matcher.group())));
        }
        matcher.appendTail(maskedMessage);

        return maskedMessage.toString();
    }
}
