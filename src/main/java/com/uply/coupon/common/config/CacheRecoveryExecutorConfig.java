package com.uply.coupon.common.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 자동 캐시 복구({@code CacheAutoRecoveryTrigger})가 실제 복구 작업을 발급 요청 스레드 밖에서 실행하기 위한 전용 스레드 풀. 발급 실패
 * 응답(503)을 지연시키지 않으려고 별도 풀을 둔다 — 요청 스레드에서 직접 복구를 돌리면 이미 실패한 요청의 응답까지 늦어진다.
 */
@Configuration
public class CacheRecoveryExecutorConfig {

    @Bean
    public Executor cacheRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cache-recovery-");
        executor.initialize();
        return executor;
    }
}
