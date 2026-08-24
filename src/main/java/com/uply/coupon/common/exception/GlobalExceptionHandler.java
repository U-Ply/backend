package com.uply.coupon.common.exception;

import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.operation.admin.BatchConflictException;
import com.uply.coupon.operation.admin.BatchExecutionNotFoundException;
import com.uply.coupon.operation.admin.BatchInvalidRequestException;
import com.uply.coupon.operation.admin.BatchNotImplementedException;
import com.uply.coupon.operation.admin.VerificationRunNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Counter concurrencyConflictCounter;
    private final Counter connectionUnavailableCounter;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.concurrencyConflictCounter =
                Counter.builder("coupon.issue.failure")
                        .tag("reason", "concurrency_conflict")
                        .description("발급 요청이 DB 수준 경합으로 실패한 횟수")
                        .register(meterRegistry);
        this.connectionUnavailableCounter =
                Counter.builder("coupon.issue.failure")
                        .tag("reason", "connection_unavailable")
                        .description("발급 요청이 DB 커넥션 획득 실패로 종료된 횟수")
                        .register(meterRegistry);
    }

    @ExceptionHandler(CouponIssueException.class)
    public ResponseEntity<ApiErrorResponse> handleCouponIssue(CouponIssueException exception) {
        IssueFailReason reason =
                Objects.requireNonNullElse(exception.getReason(), IssueFailReason.SYSTEM_ERROR);
        return switch (reason) {
            case OUT_OF_STOCK -> conflict("OUT_OF_STOCK", "쿠폰 재고가 소진되었습니다.");
            case ALREADY_ISSUED -> conflict("ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.");
            case CAMPAIGN_NOT_OPEN -> conflict("CAMPAIGN_NOT_OPEN", "아직 오픈되지 않은 캠페인입니다.");
            case CAMPAIGN_EXPIRED -> conflict("CAMPAIGN_EXPIRED", "이미 종료된 캠페인입니다.");
            case DUPLICATE_REQUEST ->
                    conflict("IDEMPOTENCY_REQUEST_IN_PROGRESS", "동일한 요청이 처리 중입니다.");
            case LOCK_TIMEOUT ->
                    response(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "LOCK_TIMEOUT",
                            "재고 처리 대기 시간이 초과되었습니다.");
            case DB_SAVE_FAILED ->
                    response(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "DB_SAVE_FAILED",
                            "쿠폰 정보 저장에 실패했습니다.");
            case KAFKA_PUBLISH_FAILED ->
                    response(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "KAFKA_PUBLISH_FAILED",
                            "이벤트 메시지 발행에 실패했습니다.");
            case SAVE_RESULT_UNKNOWN ->
                    response(
                            HttpStatus.SERVICE_UNAVAILABLE, // 불확실한 실패 -> 503
                            "SAVE_RESULT_UNKNOWN",
                            "쿠폰 발급 처리 결과가 불확실합니다. 잠시 후 발급 내역을 확인해 주세요.");
            case CONNECTION_UNAVAILABLE -> {
                connectionUnavailableCounter.increment();
                yield response(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "CONNECTION_UNAVAILABLE",
                        "일시적으로 요청을 처리할 수 없습니다.");
            }
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

    @ExceptionHandler(CouponNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> handleCouponNotReady(
            CouponNotReadyException exception) {
        return conflict(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUserNotFound(UserNotFoundException exception) {
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

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception) {
        return conflict("IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
    }

    @ExceptionHandler(BatchInvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBatchInvalidRequest(
            BatchInvalidRequestException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler({BatchConflictException.class, JobInstanceAlreadyCompleteException.class})
    public ResponseEntity<ApiErrorResponse> handleBatchConflict(Exception exception) {
        return conflict("BATCH_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(BatchNotImplementedException.class)
    public ResponseEntity<ApiErrorResponse> handleBatchNotImplemented(
            BatchNotImplementedException exception) {
        return response(
                HttpStatus.NOT_IMPLEMENTED, "BATCH_NOT_IMPLEMENTED", exception.getMessage());
    }

    @ExceptionHandler(BatchExecutionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBatchExecutionNotFound(
            BatchExecutionNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "BATCH_EXECUTION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(VerificationRunNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleVerificationRunNotFound(
            VerificationRunNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "VERIFICATION_RUN_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "잘못된 요청입니다. 다시 시도해 주세요.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<ApiErrorResponse> handleConnectionUnavailable(
            CannotCreateTransactionException exception) {
        connectionUnavailableCounter.increment();
        log.warn(
                "Connection unavailable on issue: type={}, message={}",
                exception.getClass().getSimpleName(),
                exception.getMessage());
        // 스택 트레이스는 부하 테스트 중 I/O 비용이 크므로 디버그 레벨에서만 남긴다.
        log.debug("Connection unavailable detail", exception);
        return response(
                HttpStatus.SERVICE_UNAVAILABLE, "CONNECTION_UNAVAILABLE", "일시적으로 요청을 처리할 수 없습니다.");
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrencyConflict(
            PessimisticLockingFailureException exception) {
        concurrencyConflictCounter.increment();
        log.warn(
                "Concurrency conflict on issue: type={}, message={}",
                exception.getClass().getSimpleName(),
                exception.getMessage());
        log.debug("Concurrency conflict detail", exception);
        return response(
                HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_CONFLICT", "동시 요청 경합으로 처리하지 못했습니다.");
    }

    private ResponseEntity<ApiErrorResponse> conflict(String errorCode, String message) {
        return response(HttpStatus.CONFLICT, errorCode, message);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status, String errorCode, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(errorCode, message));
    }
}
