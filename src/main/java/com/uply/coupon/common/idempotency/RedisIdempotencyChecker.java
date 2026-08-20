package com.uply.coupon.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 기반 멱등성 검사 및 응답 캐싱 구현체 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "coupon.idempotency.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RedisIdempotencyChecker implements IdempotencyChecker {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30); // 선점 락 타임아웃
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(10); // 완료 응답 캐시 유효 기간

    @Override
    public Optional<String> getCachedResponse(String idempotencyKey) {
        return getCachedResponse(idempotencyKey, "");
    }

    @Override
    public Optional<String> getCachedResponse(String idempotencyKey, String requestHash) {
        // #1. idempotencyKey 가 없으면
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        String redisKey = "idempotency:" + idempotencyKey;
        String processingValue = createProcessingJson(requestHash);

        // SETNX (setIfAbsent) 수행: 키가 없을 때만 "PROCESSING"을 원자적으로 저장
        Boolean isNewRequest =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(redisKey, processingValue, PROCESSING_TTL); // TTL 30초

        // #2. [최초 요청] 키가 없어서 성공적으로 "PROCESSING"을 저장한 경우
        // 		캐시 미스로 판단 -> Service 에서 비즈니스 로직 진행
        if (Boolean.TRUE.equals(isNewRequest)) {
            log.info("[멱등성 선점 성공] 최초 요청 진입 - key: {}", redisKey);
            return Optional.empty();
        }

        // #3. [중복 요청] 키가 이미 있는 경우 -> 캐시된 응답 얻기
        String cachedData = redisTemplate.opsForValue().get(redisKey);
        // 키는 있는데 데이터가 없는 경우 empty 반환
        if (cachedData == null) {
            return Optional.empty();
        }

        // #4. 캐시된 응답 데이터 검사
        try {
            // String -> DTO 역직렬화
            IdempotencyCache cache = objectMapper.readValue(cachedData, IdempotencyCache.class);

            if (!Objects.equals(
                    normalizeHash(requestHash), normalizeHash(cache.getRequestHash()))) {
                throw new IdempotencyKeyReusedException();
            }

            // 1. 선행 요청이 아직 처리 중인 경우 (동시성 요청 차단)
            if ("PROCESSING".equals(cache.getStatus())) {
                log.warn("[멱등성 검사] 이미 처리 중인 요청입니다. key: {}", redisKey);
                throw new IdempotencyRequestInProgressException();
            }

            // 2. 처리가 완료된 요청인 경우 캐시된 JSON body 반환
            return Optional.ofNullable(cache.getBody());

        } catch (JsonProcessingException e) {
            log.error("[멱등성 검사] Redis 캐시 역직렬화 실패 - key: {}", redisKey, e);
            return Optional.empty();
        }
    }

    @Override
    public void cacheResponse(String idempotencyKey, String responseBody, int httpStatus) {
        cacheResponse(idempotencyKey, "", responseBody, httpStatus);
    }

    @Override
    public void cacheResponse(
            String idempotencyKey, String requestHash, String responseBody, int httpStatus) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String redisKey = "idempotency:" + idempotencyKey;

        IdempotencyCache cache =
                IdempotencyCache.builder()
                        .status("COMPLETED")
                        .httpStatus(httpStatus)
                        .body(responseBody)
                        .requestHash(normalizeHash(requestHash))
                        .build();

        try {
            String jsonValue = objectMapper.writeValueAsString(cache);
            redisTemplate.opsForValue().set(redisKey, jsonValue, COMPLETED_TTL); // TTL 10분
            log.info("[멱등성 응답 캐싱 성공] key: {}, httpStatus: {}", redisKey, httpStatus);
        } catch (JsonProcessingException e) {
            log.error("[멱등성 응답 캐싱 실패] JSON 직렬화 오류 - key: {}", redisKey, e);
        }
    }

    @Override
    public void clearProgress(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String redisKey = "idempotency:" + idempotencyKey;

        try {
            Boolean deleted = redisTemplate.delete(redisKey);
            log.info("[멱등성 선점 해제 완료] key: {}, deleted: {}", redisKey, deleted);
        } catch (Exception e) {
            log.error("[멱등성 선점 해제 실패] key: {}", redisKey, e);
        }
    }

    private String createProcessingJson(String requestHash) {
        try {
            IdempotencyCache processingCache =
                    IdempotencyCache.builder()
                            .status("PROCESSING")
                            .requestHash(normalizeHash(requestHash))
                            .build();
            return objectMapper.writeValueAsString(processingCache);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PROCESSING JSON 생성 실패", e);
        }
    }

    private String normalizeHash(String requestHash) {
        return requestHash == null ? "" : requestHash;
    }
}
