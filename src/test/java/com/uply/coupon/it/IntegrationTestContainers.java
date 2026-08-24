package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 모든 통합 테스트가 공유하는 컨테이너 기반 클래스.
 *
 * <p>컨테이너는 JVM당 한 번만 시작한다.
 *
 * <p>Kafka는 Spring ApplicationContext가 만들어지기 전에 필수 topic을 명시적으로 생성하고 실제 partition 수를 검증한다.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestContainers {

    static final String DB_NAME = "coupon_db";

    /** V3 Kafka issue topic. */
    static final String ISSUE_TOPIC = "coupon-issued";

    /** V3 Kafka DLT topic. */
    static final String ISSUE_DLT_TOPIC = "coupon-issued.DLT";

    /** V3에서 요구하는 실제 partition 수. */
    static final int ISSUE_TOPIC_PARTITIONS = 3;

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0.46")
                    .withDatabaseName(DB_NAME)
                    .withUsername("coupon")
                    .withPassword("coupon1234")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("docs/schema.sql"),
                            "/docker-entrypoint-initdb.d/01-schema.sql")
                    .withCommand("--default-time-zone=+00:00", "--max-connections=500");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4.10").withExposedPorts(6379);

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        MYSQL.start();
        REDIS.start();

        /*
         * Kafka가 먼저 떠야 한다.
         */
        KAFKA.start();

        /*
         * 중요:
         *
         * Spring Context가 생성되기 전에
         * Kafka topic을 명시적으로 준비한다.
         */
        ensureKafkaTopics();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {

        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);

        registry.add("spring.datasource.username", MYSQL::getUsername);

        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);

        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");

        registry.add("spring.batch.job.enabled", () -> "false");

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.jpa.open-in-view", () -> "false");

        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");

        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");

        registry.add("coupon.reconciliation.scheduler-enabled", () -> "false");
    }

    /**
     * Kafka 필수 topic을 생성하고 실제 partition 수를 검증한다.
     *
     * <p>이미 topic이 존재하는 경우에도 단순히 무시하지 않는다. 반드시 describeTopics()로 실제 partition 수를 확인한다.
     */
    private static void ensureKafkaTopics() {

        try (AdminClient admin =
                AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {

            createTopicsIfNecessary(admin);

            assertTopicHasExpectedPartitions(admin, ISSUE_TOPIC);

            assertTopicHasExpectedPartitions(admin, ISSUE_DLT_TOPIC);

        } catch (Exception e) {
            throw new IllegalStateException("Kafka 필수 topic 초기화/검증에 실패했습니다.", e);
        }
    }

    /**
     * topic이 없으면 생성한다.
     *
     * <p>이미 존재하는 경우에만 허용한다. Kafka 연결 오류나 timeout 등의 다른 예외는 숨기지 않는다.
     */
    private static void createTopicsIfNecessary(AdminClient admin) throws Exception {

        try {

            admin.createTopics(
                            List.of(
                                    new NewTopic(ISSUE_TOPIC, ISSUE_TOPIC_PARTITIONS, (short) 1),
                                    new NewTopic(
                                            ISSUE_DLT_TOPIC, ISSUE_TOPIC_PARTITIONS, (short) 1)))
                    .all()
                    .get();

        } catch (Exception e) {

            Throwable cause = e.getCause();

            if (!(cause instanceof TopicExistsException)) {
                throw e;
            }
        }
    }

    /** broker에 실제 생성된 topic의 partition 수를 검증한다. */
    private static void assertTopicHasExpectedPartitions(AdminClient admin, String topicName)
            throws Exception {

        Map<String, TopicDescription> descriptions =
                admin.describeTopics(List.of(topicName)).allTopicNames().get();

        TopicDescription description = descriptions.get(topicName);

        assertThat(description).as("Kafka topic이 존재해야 합니다: %s", topicName).isNotNull();

        assertThat(description.partitions())
                .as("Kafka topic %s의 partition 수가 잘못되었습니다.", topicName)
                .hasSize(ISSUE_TOPIC_PARTITIONS);
    }
}
