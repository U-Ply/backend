package com.uply.coupon.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** classpath의 Lua 스크립트 파일을 {@link DefaultRedisScript}로 로드하는 공통 헬퍼. */
public final class LuaScriptLoader {

    private LuaScriptLoader() {}

    /**
     * @param classpathLocation {@code scripts/} 기준 클래스패스 경로 (예: {@code "scripts/foo.lua"})
     * @param resultType {@code EVAL} 결과를 매핑할 타입
     */
    public static <T> DefaultRedisScript<T> load(String classpathLocation, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(resultType);
        return script;
    }
}
