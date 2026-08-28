package com.uply.coupon.coupon.gate;

import com.uply.coupon.common.LuaScriptLoader;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class GateRateLimiter {

    private static final String BUCKET_KEY = "gate:issue:tokens";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> takeTokenScript;
    private final boolean enabled;
    private final String capacity;
    private final String refillPerSec;

    public GateRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${coupon.gate.enabled:false}") boolean enabled,
            @Value("${coupon.gate.capacity:4000}") long capacity,
            @Value("${coupon.gate.refill-per-sec:2000}") double refillPerSec) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        // Lua 는 ARGV 를 문자열로 받아 tonumber 하므로 여기서 문자열로 고정한다.
        this.capacity = String.valueOf(capacity);
        this.refillPerSec = String.valueOf(refillPerSec);
        this.takeTokenScript = LuaScriptLoader.load("scripts/gate_take_token.lua", Long.class);
    }

    // 토큰을 하나 소비할 수 있으면 {@code true}. 게이트가 꺼져 있으면 항상 {@code true}
    public boolean tryAcquire() {
        if (!enabled) {
            return true;
        }
        Long allowed =
                redisTemplate.execute(
                        takeTokenScript,
                        List.of(BUCKET_KEY),
                        capacity,
                        refillPerSec,
                        String.valueOf(System.currentTimeMillis()),
                        "1");
        return allowed != null && allowed == 1L;
    }
}
