package com.uply.coupon.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 멱등성 검사 및 응답 캐싱 구현체.
 *
 * <p>PROCESSING 선점마다 고유한 ownerToken을 발급한다. 삭제({@link #release})·완료({@link #complete})·TTL 연장({@link
 * #renew})은 모두 Lua로 "현재 값이 PROCESSING이고 ownerToken이 일치하는지"를 비교한 뒤에만 반영되는 Compare-And-Swap이다. 그래서
 * PROCESSING_TTL(30초)이 지나 다른 요청이 같은 키를 새로 선점해도, 뒤늦게 끝난 이전 요청이 그 새 PROCESSING을 지우거나 COMPLETED로 덮어쓰지
 * 못한다.
 *
 * <p><b>알려진 제한</b>: 이 구현은 처리 중 {@link #renew}를 자동으로 반복 호출하지 않는다. 단일 요청의 처리 시간이 PROCESSING_TTL을 넘으면
 * 그 사이 다른 요청이 같은 키를 새로 선점할 수 있다 - ownerToken이 달라 서로의 값을 훼손하지는 않지만, "동일 요청은 항상 한 번만 실행된다"는 보장은 아니게
 * 된다. 최종 방어선은 DB UNIQUE 제약이다. 완전한 보장이 필요하면 처리 중 {@link #renew}를 주기적으로(권장 10초 간격) 호출해야 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "coupon.idempotency.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RedisIdempotencyChecker implements IdempotencyChecker {

    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30); // 선점 락 타임아웃
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(10); // 완료 응답 캐시 유효 기간

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private DefaultRedisScript<Long> releaseScript;
    private DefaultRedisScript<Long> completeScript;
    private DefaultRedisScript<Long> renewScript;

    @PostConstruct
    public void init() {
        releaseScript = loadScript("scripts/idempotency_release.lua");
        completeScript = loadScript("scripts/idempotency_complete.lua");
        renewScript = loadScript("scripts/idempotency_renew.lua");
    }

    private DefaultRedisScript<Long> loadScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public IdempotencyClaim acquire(String idempotencyKey, String requestHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return IdempotencyClaim.acquired(null);
        }

        String redisKey = redisKey(idempotencyKey);
        String normalizedHash = normalizeHash(requestHash);
        String ownerToken = UUID.randomUUID().toString();
        String processingValue = createProcessingJson(ownerToken, normalizedHash);

        // SETNX (setIfAbsent) 수행: 키가 없을 때만 PROCESSING을 원자적으로 저장
        Boolean isNewRequest =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(redisKey, processingValue, PROCESSING_TTL); // TTL 30초

        // [최초 요청] 키가 없어서 성공적으로 PROCESSING을 저장한 경우
        if (Boolean.TRUE.equals(isNewRequest)) {
            log.info("[멱등성 선점 성공] 최초 요청 진입 - key: {}, ownerToken: {}", redisKey, ownerToken);
            return IdempotencyClaim.acquired(ownerToken);
        }

        // [중복 요청] 키가 이미 있는 경우 -> 캐시된 상태 확인
        String cachedData = redisTemplate.opsForValue().get(redisKey);
        // 선점하지 못한 요청만 이 경로에 진입하므로 캐시 조회 실패 시에도 중복 실행을 차단한다.
        if (cachedData == null) {
            log.warn("[멱등성 검사] 선점 실패 후 캐시 데이터가 없습니다. key: {}", redisKey);
            throw new IdempotencyRequestInProgressException();
        }

        IdempotencyCache cache;
        try {
            cache = objectMapper.readValue(cachedData, IdempotencyCache.class);
        } catch (JsonProcessingException e) {
            // 역직렬화 실패: 불완전한 캐시를 신규 요청처럼 재실행하면 안 되므로 차단한다.
            log.error("[멱등성 검사] Redis 캐시 역직렬화 실패 - key: {}", redisKey, e);
            throw new IdempotencyRequestInProgressException();
        }

        if (!Objects.equals(normalizedHash, normalizeHash(cache.getRequestHash()))) {
            throw new IdempotencyKeyReusedException();
        }

        String status = cache.getStatus();

        // 선행 요청이 아직 처리 중인 경우 (동시성 요청 차단)
        if ("PROCESSING".equals(status)) {
            log.warn("[멱등성 검사] 이미 처리 중인 요청입니다. key: {}", redisKey);
            throw new IdempotencyRequestInProgressException();
        }

        if ("COMPLETED".equals(status)) {
            if (cache.getBody() != null) {
                return IdempotencyClaim.completed(cache.getBody());
            }
            // COMPLETED인데 body가 없는 비정상 캐시: 신규 요청처럼 재실행하면 중복 처리로
            // 이어질 수 있으므로 차단한다.
            log.warn("[멱등성 검사] COMPLETED 상태인데 body가 없어 재실행을 차단합니다. key: {}", redisKey);
            throw new IdempotencyRequestInProgressException();
        }

        // 알 수 없는 status: 마찬가지로 재실행을 차단한다.
        log.warn("[멱등성 검사] 알 수 없는 status로 재실행을 차단합니다. key: {}, status: {}", redisKey, status);
        throw new IdempotencyRequestInProgressException();
    }

    @Override
    public boolean complete(
            String idempotencyKey,
            String ownerToken,
            String requestHash,
            String responseBody,
            int httpStatus) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || ownerToken == null) {
            return false;
        }

        String redisKey = redisKey(idempotencyKey);
        IdempotencyCache cache =
                IdempotencyCache.builder()
                        .status("COMPLETED")
                        .httpStatus(httpStatus)
                        .body(responseBody)
                        .requestHash(normalizeHash(requestHash))
                        .build();

        String jsonValue;
        try {
            jsonValue = objectMapper.writeValueAsString(cache);
        } catch (JsonProcessingException e) {
            log.error("[멱등성 응답 캐싱 실패] JSON 직렬화 오류 - key: {}", redisKey, e);
            return false;
        }

        Long result =
                redisTemplate.execute(
                        completeScript,
                        List.of(redisKey),
                        ownerToken,
                        normalizeHash(requestHash),
                        jsonValue,
                        String.valueOf(COMPLETED_TTL.toMillis()));

        boolean success = Long.valueOf(1L).equals(result);
        if (success) {
            log.info(
                    "[멱등성 응답 캐싱 성공] key: {}, httpStatus: {}, ownerToken: {}",
                    redisKey,
                    httpStatus,
                    ownerToken);
        } else {
            // 이 요청이 이미 소유권을 잃었다는 뜻이다. 현재 값(다른 요청의 PROCESSING/COMPLETED)을
            // 덮어쓰면 안 되므로 그대로 둔다.
            log.warn(
                    "[멱등성 응답 캐싱 실패] 소유권 상실 또는 상태·해시 불일치 - key: {}, ownerToken: {}",
                    redisKey,
                    ownerToken);
        }
        return success;
    }

    @Override
    public boolean release(String idempotencyKey, String ownerToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || ownerToken == null) {
            return false;
        }

        String redisKey = redisKey(idempotencyKey);
        try {
            Long result = redisTemplate.execute(releaseScript, List.of(redisKey), ownerToken);
            boolean success = Long.valueOf(1L).equals(result);
            log.info(
                    "[멱등성 선점 해제] key: {}, ownerToken: {}, released: {}",
                    redisKey,
                    ownerToken,
                    success);
            return success;
        } catch (Exception e) {
            log.error("[멱등성 선점 해제 실패] key: {}", redisKey, e);
            return false;
        }
    }

    @Override
    public boolean renew(String idempotencyKey, String ownerToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || ownerToken == null) {
            return false;
        }

        String redisKey = redisKey(idempotencyKey);
        Long result =
                redisTemplate.execute(
                        renewScript,
                        List.of(redisKey),
                        ownerToken,
                        String.valueOf(PROCESSING_TTL.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    private String createProcessingJson(String ownerToken, String requestHash) {
        try {
            IdempotencyCache processingCache =
                    IdempotencyCache.builder()
                            .status("PROCESSING")
                            .ownerToken(ownerToken)
                            .requestHash(requestHash)
                            .build();
            return objectMapper.writeValueAsString(processingCache);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PROCESSING JSON 생성 실패", e);
        }
    }

    private String redisKey(String idempotencyKey) {
        return "idempotency:" + idempotencyKey;
    }

    private String normalizeHash(String requestHash) {
        return requestHash == null ? "" : requestHash;
    }
}
