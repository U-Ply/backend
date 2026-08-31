package com.uply.coupon.coupon.gate;

import com.uply.coupon.coupon.api.CouponApiPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class GateWebConfig implements WebMvcConfigurer {

    private final GateInterceptor gateInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gateInterceptor).addPathPatterns(CouponApiPaths.ISSUE_URI);
    }
}
