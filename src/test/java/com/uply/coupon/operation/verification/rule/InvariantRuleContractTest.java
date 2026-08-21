package com.uply.coupon.operation.verification.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("규칙 SQL 계약")
class InvariantRuleContractTest {

    @Autowired List<InvariantRule> rules;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("규칙이 12개 이상 등록돼 있고 코드가 중복되지 않는다")
    void 등록_상태() {
        List<String> codes = rules.stream().map(InvariantRule::code).sorted().toList();
        assertThat(codes).hasSizeGreaterThanOrEqualTo(12).doesNotHaveDuplicates();
        assertThat(codes).allMatch(c -> c.matches("INV-\\d{2}"));
    }

    @TestFactory
    @DisplayName("모든 규칙 SQL이 실행되고 target_table·target_id·detail 을 반환한다")
    Stream<DynamicTest> 컬럼_계약() {
        return rules.stream()
                .map(
                        rule ->
                                DynamicTest.dynamicTest(
                                        rule.code(),
                                        () -> {

                                            // LIMIT 0 — 행은 안 읽고 컬럼 메타데이터만 본다. 데이터가 없어도 된다.
                                            List<String> cols =
                                                    jdbc.query(
                                                            "SELECT * FROM ("
                                                                    + rule.violationSql()
                                                                    + ") v LIMIT 0",
                                                            rs -> {
                                                                var md = rs.getMetaData();
                                                                List<String> names =
                                                                        new ArrayList<>();
                                                                for (int i = 1;
                                                                        i <= md.getColumnCount();
                                                                        i++) {
                                                                    names.add(md.getColumnLabel(i));
                                                                }
                                                                return names;
                                                            });

                                            assertThat(cols)
                                                    .as(rule.code() + " 의 컬럼 계약")
                                                    .containsExactlyInAnyOrder(
                                                            "target_table", "target_id", "detail");
                                        }));
    }

    @TestFactory
    @DisplayName("Runner 가 감싸는 형태 그대로 실행된다")
    Stream<DynamicTest> 러너_호환() {
        return rules.stream()
                .map(
                        rule ->
                                DynamicTest.dynamicTest(
                                        rule.code(),
                                        () -> {
                                            String body = rule.violationSql();
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT COUNT(*) FROM ("
                                                                            + body
                                                                            + ") v",
                                                                    Long.class))
                                                    .isNotNull();
                                            jdbc.query(
                                                    "SELECT * FROM ("
                                                            + body
                                                            + ") v ORDER BY target_id LIMIT 1",
                                                    rs -> {}); // ORDER BY target_id 가 되는지
                                            if (rule.checkedRowsSql() != null) {
                                                assertThat(
                                                                jdbc.queryForObject(
                                                                        rule.checkedRowsSql(),
                                                                        Long.class))
                                                        .isNotNull();
                                            }
                                        }));
    }
}
