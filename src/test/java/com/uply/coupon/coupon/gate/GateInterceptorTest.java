package com.uply.coupon.coupon.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.uply.coupon.common.metrics.GateMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class GateInterceptorTest {

    @Mock private GateRateLimiter rateLimiter;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private GateInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new GateInterceptor(rateLimiter, new GateMetrics(meterRegistry));
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private static MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/api/coupons/issue");
    }

    @Test
    void passesAndCountsWhenTokenAvailable() throws Exception {
        given(rateLimiter.tryAcquire()).willReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(post(), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(counter("coupon.gate.passed")).isEqualTo(1.0);
        assertThat(counter("coupon.gate.rejected")).isZero();
    }

    @Test
    void rejectsWith429WhenNoToken() throws Exception {
        given(rateLimiter.tryAcquire()).willReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(post(), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        assertThat(counter("coupon.gate.rejected")).isEqualTo(1.0);
        assertThat(counter("coupon.gate.passed")).isZero();
    }

    @Test
    void failsOpenWhenLimiterThrows() throws Exception {
        given(rateLimiter.tryAcquire()).willThrow(new RuntimeException("redis down"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(post(), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonPostRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/coupons/issue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
    }
}
