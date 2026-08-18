package com.uply.coupon.common.exception;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CouponIssueException.class)
    public ResponseEntity<ApiErrorResponse> handleCouponIssue(CouponIssueException exception) {
        IssueFailReason reason =
                Objects.requireNonNullElse(exception.getReason(), IssueFailReason.SYSTEM_ERROR);
        return switch (reason) {
            case OUT_OF_STOCK -> conflict("OUT_OF_STOCK", "쿠폰 재고가 소진되었습니다.");
            case ALREADY_ISSUED -> conflict("ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.");
            case CAMPAIGN_NOT_OPEN -> conflict("CAMPAIGN_NOT_OPEN", "아직 오픈되지 않은 캠페인입니다.");
            case DUPLICATE_REQUEST ->
                    conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS", "동일한 요청이 처리 중입니다.");
            case LOCK_TIMEOUT ->
                    response(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "LOCK_TIMEOUT",
                            "재고 처리 대기 시간이 초과되었습니다.");
            case SYSTEM_ERROR ->
                    response(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_SERVER_ERROR",
                            "서버 내부 오류가 발생했습니다.");
		default -> throw new IllegalArgumentException("Unexpected value: " + reason);
        };
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCampaignNotFound(
            CampaignNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(CampaignNotOpenException.class)
    public ResponseEntity<ApiErrorResponse> handleCampaignNotOpen(
            CampaignNotOpenException exception) {
        return conflict("CAMPAIGN_NOT_OPEN", exception.getMessage());
    }

    @ExceptionHandler(CouponNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCouponNotFound(
            CouponNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidStateTransition(
            InvalidStateTransitionException exception) {
        return conflict(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(IdempotencyRequestInProgressException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyRequestInProgress(
            IdempotencyRequestInProgressException exception) {
        return conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS", exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
    }

    private ResponseEntity<ApiErrorResponse> conflict(String errorCode, String message) {
        return response(HttpStatus.CONFLICT, errorCode, message);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, message));
    }
}
