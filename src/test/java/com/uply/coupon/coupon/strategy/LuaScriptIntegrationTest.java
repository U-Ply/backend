package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@DataRedisTest
class LuaScriptIntegrationTest {

    @Autowired private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<List> issueScript;
    private final List<String> createdKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        issueScript = new DefaultRedisScript<>();
        issueScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/issue_coupon.lua")));
        issueScript.setResultType(List.class);
    }

    @AfterEach
    void tearDown() {
        // FLUSHDB 대신 본 테스트에서 사용한 Key만 타겟팅 삭제 (원자적 정리)
        if (!createdKeys.isEmpty()) {
            redisTemplate.delete(createdKeys);
            createdKeys.clear();
        }
    }

    @Test
    @DisplayName("재고 키(stock:{stockId})가 없으면 openAt/expireAt이 존재해도 -3(CAMPAIGN_NOT_CACHED)을 반환한다")
    void script_StockKeyMissing_ReturnsMinusThree() {
        // given: 테스트 격리를 위한 고유 ID 사용
        Long campaignId = 1001L;
        Long stockId = 9999L;
        Long userId = 100L;

        String stockKey = "stock:" + stockId; // 의도적으로 Redis에 저장하지 않음
        String issuedKey = "issued:" + campaignId;
        String openAtKey = "campaign:" + campaignId + ":openAt";
        String expireAtKey = "campaign:" + campaignId + ":expireAt";

        // 재고 키 누락 검증을 위해 openAt, expireAt은 정상값으로 적재 (외생 변수 통제)
        redisTemplate
                .opsForValue()
                .set(openAtKey, String.valueOf(System.currentTimeMillis() - 10000));
        redisTemplate
                .opsForValue()
                .set(expireAtKey, String.valueOf(System.currentTimeMillis() + 10000));

        List<String> keys = List.of(stockKey, issuedKey, openAtKey, expireAtKey);
        createdKeys.addAll(keys);

        // when
        List<Object> result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));

        // then
        assertThat(result).isNotNull();
        assertThat((Long) result.get(0)).isEqualTo(-3L);
    }

    @Test
    @DisplayName("재고 키는 존재하지만 남아있는 재고가 0이면 -2(OUT_OF_STOCK)를 반환한다")
    void script_StockIsZero_ReturnsMinusTwo() {
        // given: 다른 테스트와 겹치지 않는 고유 ID 사용
        Long campaignId = 1002L;
        Long stockId = 1002L;
        Long userId = 100L;

        String stockKey = "stock:" + stockId;
        String issuedKey = "issued:" + campaignId;
        String openAtKey = "campaign:" + campaignId + ":openAt";
        String expireAtKey = "campaign:" + campaignId + ":expireAt";

        redisTemplate.opsForValue().set(stockKey, "0");
        redisTemplate
                .opsForValue()
                .set(openAtKey, String.valueOf(System.currentTimeMillis() - 10000));
        redisTemplate
                .opsForValue()
                .set(expireAtKey, String.valueOf(System.currentTimeMillis() + 10000));

        List<String> keys = List.of(stockKey, issuedKey, openAtKey, expireAtKey);
        createdKeys.addAll(keys);

        // when
        List<Object> result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));

        // then
        assertThat(result).isNotNull();
        assertThat((Long) result.get(0)).isEqualTo(-2L);
    }
}
