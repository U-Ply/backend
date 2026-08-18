package com.uply.coupon.common.config;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.TimeZone;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    void setDefaultTimeZoneToUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        log.info("JVM 기본 타임존을 UTC로 고정했다. LocalDateTime.now()={}", LocalDateTime.now());
    }
}
