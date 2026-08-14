package com.uply.coupon.coupon.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.coupon.dto.IdempotencyCache;
import io.hypersistence.tsid.TSID;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/** Redis + Lua Script 기반 발급 전략 */
@Component("luaScriptIssueStrategy")
@Slf4j
@RequiredArgsConstructor
public class LuaScriptIssueStrategy implements CouponIssueStrategy {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper; // JSON 변환용

    private DefaultRedisScript<List> issueScript;

    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(10); // 규약: TTL 10분

    @PostConstruct
    public void init() {
        issueScript = new DefaultRedisScript<>();
        issueScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/issue_coupon.lua")));
        issueScript.setResultType(List.class);
    }

    /**
     * Redis 캐시에 CampaignStock 데이터가 이미 적재되어 있는 상태. stockId, remainingStock 만 사용 쿠폰 발급 성공 시 stockId 를
     * IssueResult 에 포함해야 Kafka 메시지로 전달 가능.
     */
    @Override
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: Lua Script로 재고 확인 + 중복 체크 + 차감을 원자적으로 처리 후 Kafka 이벤트 발행

        String idempotencyRedisKey = null;

        // 1. [Idempotency Key 검증 및 조회]
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // 규약 키 패턴: idempotency:{apiType}:{key}
            idempotencyRedisKey = String.format("idempotency:%s", idempotencyKey);

            String cachedData = redisTemplate.opsForValue().get(idempotencyRedisKey);
            if (cachedData != null) {
                log.info("[멱등성 처리] 이미 처리된 요청입니다. key: {}", idempotencyRedisKey);
                // 이전 처리 결과 캐시가 존재하면 중복 요청으로 차단 (또는 필요시 cachedData deserialization 후 결과 재반환)
                return IssueResult.fail(IssueFailReason.DUPLICATE_REQUEST);
            }
        }

        // #1. 번호표(couponId) 사전 생성 (TSID)
        Long couponId = TSID.fast().toLong();

        // #2. Redis Key 생성
        // stockId key
        String stockIdKey = String.format("stock:%d", stockId);
        // 캠페인 중복 검사 Key: coupon:issued:{campaignId}
        String issuedCampaignKey = String.format("issued:%d", campaignId);

        // #4. Lua Script 실행 (Atomic 연산)
        // Spring Data Redis의 execute() 메서드가 타입 정보가 없는 Raw Type List를 반환하기 때문에 발생하는 컴파일러 경고
        // -> 무시해도 된다.
        List<Object> result =
                redisTemplate.execute(
                        issueScript,
                        List.of(stockIdKey, issuedCampaignKey),
                        String.valueOf(userId));

        if (result == null || result.isEmpty()) {
            return IssueResult.fail(IssueFailReason.SYSTEM_ERROR);
        }

        long resultCode = (Long) result.get(0);

        // #5. 실패인 경우 처리
        if (resultCode != 1) {
            IssueFailReason failReason = matchFailReason(resultCode);
            return IssueResult.fail(failReason);
        }

        //        // #7. Kafka 비동기 이벤트 발행 (DB Insert용)
        //        CouponIssuedEvent event = new CouponIssuedEvent(
        //                couponId,
        //                stockId,
        //                userId,
        //                campaignId,
        //                routeId,
        //                fareClassId
        //        );
        //        kafkaTemplate.send("coupon-issued-topic", String.valueOf(couponId), event);

        // #8. [Idempotency 캐시 저장] 규약에 맞춘 JSON 응답 구조 저장 (TTL 10분)
        if (idempotencyRedisKey != null) {
            saveIdempotencyCache(idempotencyRedisKey, couponId, stockId);
        }

        // #9. 성공 결과 반환 (IssueResult)
        return IssueResult.success(couponId);
    }

    @Override
    public String name() {
        return "LUA_SCRIPT";
    }

    /** [Idempotency 캐시 저장] 규약에 맞춘 JSON 응답 구조 저장 */
    private void saveIdempotencyCache(String redisKey, Long couponId, Long stockId) {
        try {
            // 규약 포맷: {"httpStatus":200,"body":"...","requestHash":"..."}
            String bodyJson = String.format("{\"couponId\":%d,\"stockId\":%d}", couponId, stockId);

            IdempotencyCache cache =
                    IdempotencyCache.builder()
                            .httpStatus(200)
                            .body(bodyJson)
                            .requestHash("") // 필요 시 요청 파라미터 해시값 세팅
                            .build();

            String jsonValue = objectMapper.writeValueAsString(cache);
            redisTemplate.opsForValue().set(redisKey, jsonValue, IDEMPOTENCY_TTL);
        } catch (JsonProcessingException e) {
            log.error("Idempotency 캐시 JSON 변환 실패", e);
        }
    }

    private IssueFailReason matchFailReason(long resultCode) {
        return switch ((int) resultCode) {
            case -1 -> IssueFailReason.ALREADY_ISSUED;
            case -2 -> IssueFailReason.OUT_OF_STOCK;
            default -> IssueFailReason.SYSTEM_ERROR;
        };
    }
}
