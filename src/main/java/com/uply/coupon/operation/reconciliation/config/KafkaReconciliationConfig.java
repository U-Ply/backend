package com.uply.coupon.operation.reconciliation.config;

import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "coupon.save.strategy", havingValue = "kafka")
public class KafkaReconciliationConfig {

    @Bean(destroyMethod = "close")
    public AdminClient reconciliationKafkaAdminClient(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return AdminClient.create(properties);
    }
}
