package com.uply.coupon.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

class IdempotencyCheckerConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            DependencyConfiguration.class,
                            RedisIdempotencyChecker.class,
                            NoOpIdempotencyChecker.class);

    @Test
    void redisCheckerIsDefault() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(IdempotencyChecker.class);
                    assertThat(context.getBean(IdempotencyChecker.class))
                            .isInstanceOf(RedisIdempotencyChecker.class);
                });
    }

    @Test
    void noOpCheckerIsUsedWhenDisabled() {
        contextRunner
                .withPropertyValues("coupon.idempotency.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(IdempotencyChecker.class);
                            assertThat(context.getBean(IdempotencyChecker.class))
                                    .isInstanceOf(NoOpIdempotencyChecker.class);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class DependencyConfiguration {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
