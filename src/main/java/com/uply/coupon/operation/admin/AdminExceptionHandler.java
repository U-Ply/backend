package com.uply.coupon.operation.admin;

import java.util.Map;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 관리자 API 전용. 배치 실행 거절 사유를 상태 코드로 구분한다. 전부 500 으로 뭉뚱그리면 "내 요청이 잘못된 것" 과 "서버가 고장난 것" 을 구별할 수 없다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AdminBatchController.class)
public class AdminExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** 이미 실행 중이거나, 같은 runId 로 이미 성공한 회차다. 재시도로 풀리지 않으므로 409. */
    @ExceptionHandler({IllegalStateException.class, JobInstanceAlreadyCompleteException.class})
    public ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** 경로는 예약돼 있으나 아직 만들지 않은 배치. */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<?> notImplemented(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * 경로 변수·쿼리 파라미터 타입이 안 맞는 경우. 서버 고장이 아니라 잘못된 요청이다. 이걸 안 잡으면 GlobalExceptionHandler 가 500 으로
     * 처리해서, 인수 기준의 "기타 5xx 0건" 이 클라이언트 실수로도 깨진다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", e.getName() + " 값이 올바르지 않다: " + e.getValue()));
    }
}
