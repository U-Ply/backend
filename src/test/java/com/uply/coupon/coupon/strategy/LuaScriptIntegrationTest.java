package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@SpringBootTest
class LuaScriptIntegrationTest {

    @Autowired private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<List> issueScript;

    @BeforeEach
    void setUp() {
        issueScript = new DefaultRedisScript<>();
        issueScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/issue_coupon.lua")));
        issueScript.setResultType(List.class);

        // 테스트 전 Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("Redis에 재고 키(stock:{stockId})가 존재하지 않으면 -3(CAMPAIGN_NOT_CACHED)을 반환한다")
    void script_StockKeyMissing_ReturnsMinusThree() {
        // given
        Long campaignId = 1L;
        Long stockId = 999L; // 존재하지 않는 stockId
        Long userId = 100L;

        List<String> keys =
                List.of(
                        "stock:" + stockId,
                        "issued:" + campaignId,
                        "campaign:" + campaignId + ":openAt",
                        "campaign:" + campaignId + ":expireAt");

        // when (실제 Redis에서 Lua Script 실행)
        List<Object> result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));

        // then
        assertThat(result).isNotNull();
        assertThat((Long) result.get(0)).isEqualTo(-3L);
    }

    @Test
    @DisplayName("재고 키는 존재하지만 남아있는 재고가 0이면 -2(OUT_OF_STOCK)를 반환한다")
    void script_StockIsZero_ReturnsMinusTwo() {
        // given
        Long campaignId = 1L;
        Long stockId = 10L;
        Long userId = 100L;

        String stockKey = "stock:" + stockId;
        String openAtKey = "campaign:" + campaignId + ":openAt";
        String expireAtKey = "campaign:" + campaignId + ":expireAt";

        // Redis에 재고 0 및 필수 키 사전 적재
        redisTemplate.opsForValue().set(stockKey, "0");
        redisTemplate
                .opsForValue()
                .set(openAtKey, String.valueOf(System.currentTimeMillis() - 10000));
        redisTemplate
                .opsForValue()
                .set(expireAtKey, String.valueOf(System.currentTimeMillis() + 10000));

        List<String> keys = List.of(stockKey, "issued:" + campaignId, openAtKey, expireAtKey);

        // when
        List<Object> result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));

        // then
        assertThat(result).isNotNull();
        assertThat((Long) result.get(0)).isEqualTo(-2L);
    }
}
