package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
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
 *
 * <p>Kafka consumer는 기본적으로 꺼져 있다. application-integration-test.yml 에서 {@code
 * coupon.kafka.consumer.enabled=false} 로 둔다. Spring이 테스트 컨텍스트를 캐시하고 닫지 않으므로, 켜 두면 JVM 안의 모든 컨텍스트가
 * 같은 group-id로 동시에 붙어 서로의 메시지를 가져간다. 켜는 것은 Kafka 소비가 검증 대상인 V3 하나뿐이고, V3는 {@link
 * #assertIssueConsumerGroupIsExclusive()} 로 그 사실을 실제 broker에 확인한다.
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

    /**
     * 애플리케이션 consumer group. KafkaConsumerConfig 와 application.yml 이 이 값을 쓴다.
     *
     * <p>여기서 group 이름을 새로 만들지 않는다. 테스트만 다른 group 을 쓰면 "테스트에서는 격리됐다"가 되고 실제 설정은 검증되지 않는다. 실제 group 을
     * 그대로 쓰되, 그 group 에 컨슈머가 하나뿐인지를 확인한다.
     */
    static final String ISSUE_CONSUMER_GROUP = "coupon-service";

    /** consumer group 멤버가 하나로 정리되기를 기다리는 최대 시간. 리밸런싱이 끝나는 데 몇 초가 걸린다. */
    private static final Duration CONSUMER_GROUP_SETTLE_TIMEOUT = Duration.ofSeconds(30);

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

        /*
         * coupon.kafka.consumer.enabled 는 여기서 등록하지 않는다.
         *
         * @DynamicPropertySource 는 @SpringBootTest(properties = ...) 보다 우선순위가 높아서
         * 여기에 false 로 넣으면 V3 가 인라인 프로퍼티로 다시 켤 수 없다.
         * 기본값 false 는 application-integration-test.yml 에 둔다.
         */
    }

    /**
     * 이 JVM 의 Kafka consumer group 에 컨슈머가 정확히 하나만 있는지 broker 에 직접 확인한다.
     *
     * <p>Kafka 소비를 검증하는 회차(V3)가 호출한다. 통과하지 못하면 그 회차의 결과는 Kafka 경로의 근거가 되지 못한다. 다른 컨텍스트의 컨슈머가 같은
     * group 에 붙어 있으면 파티션을 나눠 갖고, 어떤 메시지를 누가 처리했는지가 실행마다 달라지기 때문이다.
     *
     * <p>기다리기만 하지 않는다. 제한 시간 안에 멤버가 하나로 정리되지 않으면 현재 멤버 목록을 그대로 실패 메시지에 담아 실패한다.
     */
    protected static void assertIssueConsumerGroupIsExclusive() {

        try (AdminClient admin =
                AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {

            await().atMost(CONSUMER_GROUP_SETTLE_TIMEOUT)
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                List<String> members = consumerGroupMembers(admin);

                                assertThat(members)
                                        .as(
                                                "Kafka consumer group '%s' 에는 이 회차의 컨슈머 하나만 있어야"
                                                        + " 합니다. 둘 이상이면 파티션을 나눠 갖게 되어 결과를 신뢰할 수"
                                                        + " 없습니다. 현재 멤버=%s",
                                                ISSUE_CONSUMER_GROUP, members)
                                        .hasSize(1);
                            });
        }
    }

    /** group 에 현재 붙어 있는 컨슈머 id 목록. group 이 아직 없으면 빈 목록이다. */
    private static List<String> consumerGroupMembers(AdminClient admin) throws Exception {

        ConsumerGroupDescription description =
                admin.describeConsumerGroups(List.of(ISSUE_CONSUMER_GROUP))
                        .all()
                        .get()
                        .get(ISSUE_CONSUMER_GROUP);

        if (description == null) {
            return List.of();
        }

        return description.members().stream().map(MemberDescription::consumerId).toList();
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
