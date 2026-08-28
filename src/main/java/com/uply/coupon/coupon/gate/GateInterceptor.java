package com.uply.coupon.coupon.gate;

import com.uply.coupon.common.metrics.GateMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class GateInterceptor implements HandlerInterceptor {

    private static final String REJECTED_BODY =
            "{\"error\":\"RATE_LIMITED\",\"message\":\"요청이 많아 잠시 후 다시 시도해 주세요.\"}";

    private final GateRateLimiter rateLimiter;
    private final GateMetrics gateMetrics;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire();
        } catch (Exception e) {
            log.warn("게이트 레이트 리미터 조회 실패 - fail-open 으로 통과시킵니다.", e);
            return true;
        }

        if (allowed) {
            gateMetrics.passed();
            return true;
        }

        gateMetrics.rejected();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(REJECTED_BODY);
        return false;
    }
}
