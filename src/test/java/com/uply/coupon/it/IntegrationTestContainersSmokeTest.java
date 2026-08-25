package com.uply.coupon.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class IntegrationTestContainersSmokeTest extends IntegrationTestContainers {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Environment env;

    @Test
    void containers_are_running() {
        assertThat(MYSQL.isRunning()).as("MySQL container").isTrue();
        assertThat(REDIS.isRunning()).as("Redis container").isTrue();
        assertThat(KAFKA.isRunning()).as("Kafka container").isTrue();

        assertThat(MYSQL.getJdbcUrl()).isNotBlank();
        assertThat(REDIS.getFirstMappedPort()).isPositive();
        assertThat(KAFKA.getBootstrapServers()).isNotBlank();
    }

    /**
     * 컨테이너가 떠 있는 것과 애플리케이션이 그 컨테이너를 쓰는 것은 다른 문제다.
     *
     * <p>테스트가 DriverManager 로 직접 연결해서 SELECT 1 을 확인하면, DynamicPropertySource 가 통째로 망가져서 Spring 이 다른
     * DB 에 붙어 있어도 초록불이 된다. 여기서는 애플리케이션 자신의 DataSource / RedisTemplate / Environment 만 본다.
     */
    @Test
    void spring_is_wired_to_the_containers() throws Exception {
        // 1. 앱의 DataSource 가 이 컨테이너를 가리키는가
        assertThat(dataSource.unwrap(HikariDataSource.class).getJdbcUrl())
                .as("application datasource must point at the MySQL container")
                .isEqualTo(MYSQL.getJdbcUrl());

        // 2. 실제로 연결된 스키마가 스크립트가 만든 그 스키마인가.
        //    docs/schema.sql 이 USE coupon_db 로 스키마를 스스로 고르기 때문에,
        //    컨테이너의 databaseName 과 실제 테이블이 사는 곳이 갈라질 수 있다.
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DB_NAME);

        // 3. Redis 왕복이 앱의 템플릿을 통해 실제로 되는가
        redis.opsForValue().set("smoke:wiring", "ok");
        assertThat(redis.opsForValue().get("smoke:wiring")).isEqualTo("ok");
        redis.delete("smoke:wiring");

        // 4. Kafka 부트스트랩 주소가 컨테이너 것으로 주입됐는가
        assertThat(env.getProperty("spring.kafka.bootstrap-servers"))
                .isEqualTo(KAFKA.getBootstrapServers());
    }

    /**
     * docs/schema.sql 이 실제로 실행됐고, 테이블이 앱이 연결한 스키마에 생겼는지 본다.
     *
     * <p>ddl-auto=validate 가 통과했다는 사실만으로도 간접 증거는 되지만, 그건 컨텍스트 로딩 중에 일어나는 일이라 실패했을 때 원인이 스키마인지 다른
     * 빈인지 구분되지 않는다. 여기서 먼저 드러나게 한다.
     */
    @Test
    void schema_is_initialised() {
        Integer tables =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM information_schema.tables
                         WHERE table_schema = ?
                           AND table_name IN
                               ('users', 'campaigns', 'campaign_stocks',
                                'coupons', 'coupon_history',
                                'verification_report', 'verification_violation')
                        """,
                        Integer.class,
                        DB_NAME);

        assertThat(tables)
                .as("schema.sql must create every table the verification batch reads")
                .isEqualTo(7);
    }
}
