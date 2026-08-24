package com.uply.coupon.it;

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
 * <p><b>컨테이너 수명 — singleton 패턴</b>
 *
 * <p>@Testcontainers / @Container 를 일부러 쓰지 않는다. 두 애너테이션을 붙이면 컨테이너가 구상 테스트 클래스마다 beforeAll 에서 뜨고
 * afterAll 에서 내려간다. 필드는 static 으로 공유되므로, 클래스가 7개면 MySQL·Redis·Kafka 를 7번 재기동하게 된다.
 *
 * <p>느린 것보다 나쁜 문제가 따로 있다. Spring 은 ApplicationContext 를 캐시하고 테스트 클래스가 끝나도 닫지 않는다. 앞 클래스가 만든 컨텍스트는
 * 이미 죽은 컨테이너를 가리키는 커넥션 풀을 든 채로 캐시에 남는다. 지금은 클래스마다 properties 가 달라 캐시 키가 겹치지 않아 우연히 굴러가지만, 키가 겹치는
 * 클래스가 하나 추가되는 순간 죽은 컨테이너에 연결된 컨텍스트를 재사용하게 된다. 원인을 찾기 매우 어려운 종류의 실패다.
 *
 * <p>그래서 static 초기화 블록에서 한 번만 띄운다. 컨테이너는 JVM 이 사는 동안 유지되고, 종료 시 Testcontainers 의 Ryuk 컨테이너가 정리한다.
 * 명시적인 stop() 은 넣지 않는다 — 먼저 끝난 클래스가 아직 살아 있는 컨텍스트의 컨테이너를 내려버린다.
 *
 * <p><b>스키마 적재 — withInitScript 를 쓰지 않는 이유</b>
 *
 * <p>withInitScript 는 클래스패스에서 읽으므로 docs/schema.sql 을 src/test/resources 로 복사해 두 벌로 관리해야 한다. 두 벌은
 * 반드시 갈라지고, ddl-auto=validate 는 엔티티와만 대조하므로 그 드리프트를 잡지 못한다. 원본 한 벌을 컨테이너의 initdb 디렉터리로 그대로 넣는다.
 *
 * <p>initdb 로 넣는 편이 파싱 면에서도 안전하다. withInitScript 는 Testcontainers 의 ScriptUtils 가 문을 직접 쪼개 JDBC 로
 * 실행하지만, initdb 는 컨테이너 안의 mysql 클라이언트가 스크립트를 그대로 읽는다. 생성 컬럼, 트리거, DELIMITER 같은 구문에서 차이가 난다.
 *
 * <p><b>커넥션 풀 — application.yml 과 같은 값을 쓴다</b>
 *
 * <p>풀을 크게 열면 테스트는 쉽게 초록불이 되지만, 그 대가로 풀 고갈 계열의 결함을 못 보게 된다. 실제로 이 프로젝트에서 회차 중에 잡힌 결함 하나가 그것이었다 — 풀이
 * 마르면서 재고가 새고, REC-01 이 Redis·DB 재고 불일치로 잡아냈다. 풀을 50으로 열어두면 그 결함은 이 테스트를 그냥 통과한다.
 *
 * <p>그래서 application.yml 의 기준값(pool 10 / timeout 3000)을 그대로 쓴다. test-plan 6.1 의 풀 설정 고정과도 같은 값이다. 이
 * 설정에서 회차가 깨지면 그건 테스트 환경 문제가 아니라 기록해야 할 사실이다 (test-plan 8.2). 값을 올려서 넘기지 말고, 올렸다면 올린 값을 회차 기록에 남긴다.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestContainers {

    /**
     * 컨테이너 안의 DB 이름. 개발용 coupon_db 와 같은 이름이지만 완전히 별개의 일회용 컨테이너다.
     *
     * <p>이름을 coupon_it 으로 바꾸지 않는 이유: docs/schema.sql 이 스스로 USE coupon_db 를 하므로, 다른 이름을 주면 컨테이너는 그
     * DB 를 만들지만 테이블은 coupon_db 에 생긴다. 그때 나오는 오류는 "테이블 없음" 이라 원인을 가리키지 않는다. schema.sql 에서 CREATE
     * DATABASE / USE 를 걷어내면 그때 바꾼다.
     */
    static final String DB_NAME = "coupon_db";

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0.46")
                    .withDatabaseName(DB_NAME)
                    .withUsername("coupon")
                    .withPassword("coupon1234")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("docs/schema.sql"),
                            "/docker-entrypoint-initdb.d/01-schema.sql")
                    // max_connections 기본값은 151 이다. Spring 은 테스트 클래스가 끝나도
                    // ApplicationContext 를 닫지 않고 캐시하므로, 클래스마다 커넥션 풀이
                    // 하나씩 살아남아 같은 MySQL 한 대에 쌓인다. 풀이 10 이라도 클래스가
                    // 늘면 언젠가 닿는 한도라, 일회용 컨테이너에서는 넉넉히 열어 둔다.
                    .withCommand("--default-time-zone=+00:00", "--max-connections=500");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4.10").withExposedPorts(6379);

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        MYSQL.start();
        REDIS.start();
        KAFKA.start();
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

        // application.yml 과 같은 값. 위 클래스 주석의 "커넥션 풀" 절 참고.
        // 여기를 올리면 풀 고갈 계열 결함이 이 테스트를 그냥 통과한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");

        registry.add("coupon.reconciliation.scheduler-enabled", () -> "false");
    }
}
