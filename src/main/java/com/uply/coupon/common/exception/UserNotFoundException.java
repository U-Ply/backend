package com.uply.coupon.common.exception;

public class UserNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "USER_NOT_FOUND";

    private final Long userId;

    public UserNotFoundException(Long userId) {
        super("User not found: " + userId);
        this.userId = userId;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public Long getUserId() {
        return userId;
    }
}
