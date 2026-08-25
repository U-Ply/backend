package com.uply.coupon.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaConsumerConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
                    .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                    .withUserConfiguration(KafkaConsumerConfig.class);

    // sync-db 저장 전략에서는 Kafka Consumer 설정과 관련 Bean을 생성하지 않는지 확인
    @Test
    void consumerInfrastructureBeansAreNotCreatedForSyncDbStrategy() {
        contextRunner
                .withPropertyValues("coupon.save.strategy=sync-db")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(KafkaConsumerConfig.class);
                            assertThat(context).doesNotHaveBean("consumerFactory");
                            assertThat(context).doesNotHaveBean("kafkaListenerContainerFactory");
                        });
    }

    // kafka 저장 전략에서는 Kafka Consumer 설정과 관련 Bean을 모두 생성하는지 확인
    @Test
    void consumerInfrastructureBeansAreCreatedForKafkaStrategy() {
        contextRunner
                .withPropertyValues("coupon.save.strategy=kafka")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(KafkaConsumerConfig.class);
                            assertThat(context).hasSingleBean(DefaultKafkaConsumerFactory.class);
                            assertThat(context)
                                    .hasSingleBean(ConcurrentKafkaListenerContainerFactory.class);
                        });
    }
}
