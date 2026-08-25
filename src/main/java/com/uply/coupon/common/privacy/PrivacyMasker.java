package com.uply.coupon.common.privacy;

/** 로그에 개인정보 원문이 노출되지 않도록 변환하는 유틸리티. */
public final class PrivacyMasker {

    private static final String FULLY_MASKED = "***";

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
}
