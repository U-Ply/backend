package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.uply.coupon.it.IntegrationTestContainers;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * rollback_coupon.lua 의 실제 동작 검증.
 *
 * <p>보상은 Kafka 발행이 확정 실패했을 때만 도는 경로라 평소에 실행되지 않는다. 그래서 잘못 짜여 있어도 다른 테스트에서 드러나지 않는다. 특히 "두 번 불려도 재고가
 * 한 번만 올라간다"는 성질은 실제 Redis 없이는 증명할 수 없다. SREM 반환값으로 게이트하는 구조가 그 성질을 만드는데, 모의로는 그 상호작용이 그대로 재현되지 않기
 * 때문이다.
 */
class RollbackCouponScriptIntegrationTest extends IntegrationTestContainers {

    private static final Long CAMPAIGN_ID = 900L;
    private static final Long STOCK_ID = 910L;
    private static final String USER_ID = "100";

    private static final String STOCK_KEY = "stock:" + STOCK_ID;
    private static final String ISSUED_KEY = "issued:" + CAMPAIGN_ID;

    @Autowired private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<Long> rollbackScript;

    @BeforeEach
    void setUp() {
        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/rollback_coupon.lua")));
        rollbackScript.setResultType(Long.class);

        // 발급이 한 건 성립한 직후 상태: 재고 10 -> 9, 발급 Set에 유저 100
        redisTemplate.opsForValue().set(STOCK_KEY, "9");
        redisTemplate.opsForSet().add(ISSUED_KEY, USER_ID);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(List.of(STOCK_KEY, ISSUED_KEY));
    }

    @Test
    @DisplayName("보상 1회 실행 시 재고가 1 복구되고 발급 Set에서 유저가 제거되며 1을 반환한다")
    void rollback_firstCall_restoresStockAndRemovesUser() {
        // when
        Long result = execute();

        // then
        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(ISSUED_KEY, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("보상이 두 번 실행되어도 재고는 한 번만 복구되고 두 번째는 0을 반환한다")
    void rollback_secondCall_isNoOp() {
        // given
        assertThat(execute()).isEqualTo(1L);

        // when
        Long secondResult = execute();

        // then
        // 두 번째 호출에서 INCRBY가 또 돌면 재고가 11이 되어 초과 발급으로 이어진다.
        // SREM이 0을 반환하는 경우 재고를 건드리지 않는 것이 이 스크립트의 핵심이다.
        assertThat(secondResult).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("10");
    }

    @Test
    @DisplayName("발급 이력이 없는 유저에 대한 보상은 재고를 건드리지 않고 0을 반환한다")
    void rollback_userNeverIssued_isNoOp() {
        // given
        redisTemplate.opsForSet().remove(ISSUED_KEY, USER_ID);

        // when
        Long result = execute();

        // then
        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY)).isEqualTo("9");
    }

    private Long execute() {
        return redisTemplate.execute(rollbackScript, List.of(STOCK_KEY, ISSUED_KEY), USER_ID);
    }
}
