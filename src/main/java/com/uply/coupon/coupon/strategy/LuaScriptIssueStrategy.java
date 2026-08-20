package com.uply.coupon.coupon.strategy;

import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.common.exception.CouponIssueException;
import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/** Redis + Lua Script 기반 발급 전략 */
@Component("luaScriptIssueStrategy")
@Slf4j
@RequiredArgsConstructor
public class LuaScriptIssueStrategy implements CouponIssueStrategy {

    private final StringRedisTemplate redisTemplate;
    private final CouponIdGenerator couponIdGenerator;
    private final CouponSaveStrategy couponSaveStrategy;
    private final MeterRegistry meterRegistry;

    private DefaultRedisScript<List> issueScript;
    DefaultRedisScript<Long> rollbackScript;

    @PostConstruct
    public void init() {
        issueScript = new DefaultRedisScript<>();
        issueScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/issue_coupon.lua")));
        issueScript.setResultType(List.class);

        // 보상 전용 스크립트 실행 (1회만 반영되도록 멱등성 보장)
        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("scripts/rollback_coupon.lua")));
        rollbackScript.setResultType(Long.class);
    }

    /**
     * Redis 캐시에 CampaignStock 데이터가 이미 적재되어 있는 상태. stockId, remainingStock 만 사용 쿠폰 발급 성공 시 stockId 를
     * IssueResult 에 포함해야 Kafka 메시지로 전달 가능.
     */
    @Override
    public IssueResult issue(Long campaignId, Long userId, Long stockId, String idempotencyKey) {
        // TODO: Lua Script로 재고 확인 + 중복 체크 + 차감을 원자적으로 처리 후 Kafka 이벤트 발행

        // #1. 번호표(couponId) 사전 생성 (TSID)
        Long couponId = couponIdGenerator.generate();

        // #2. Redis Key 생성
        // stockId key
        String stockIdKey = String.format("stock:%d", stockId);
        // 캠페인 중복 검사 Key: coupon:issued:{campaignId}
        String issuedCampaignKey = String.format("issued:%d", campaignId);
        // 캠페인 오픈 시각 Key
        String campaignOpenAtKey = String.format("campaign:%d:openAt", campaignId);
        // Redis 캐싱된 expireAt 조회
        LocalDateTime expireAt = getExpireAt(campaignId); // 실패 가능

        // #3. Lua Script 실행 (Atomic 연산)
        // Spring Data Redis의 execute() 메서드가 타입 정보가 없는 Raw Type List를 반환하기 때문에 발생하는 컴파일러 경고
        // -> 무시해도 된다.
        List<Object> result =
                redisTemplate.execute(
                        issueScript,
                        List.of(stockIdKey, issuedCampaignKey, campaignOpenAtKey),
                        String.valueOf(userId));

        if (result == null || result.isEmpty()) {
            return IssueResult.fail(IssueFailReason.SYSTEM_ERROR);
        }

        long resultCode = (Long) result.get(0);

        // #4. 실패인 경우 처리
        if (resultCode != 1) {
            IssueFailReason failReason = matchFailReason(resultCode);
            return IssueResult.fail(failReason);
        }


        // #5. DB 저장 전략 선택 : 동기 / Kafka 비동기
        try {
            couponSaveStrategy.save(
                    couponId, userId, campaignId, stockId, idempotencyKey, expireAt);

        } catch (CouponIssueException e) {
            // 결과가 불명확한 타임아웃(SAVE_RESULT_UNKNOWN) 발생 시 Redis 보상 유예 (초과 발급 방지)
            if (e.getReason() == IssueFailReason.SAVE_RESULT_UNKNOWN) {
                log.warn("[발행 결과 불명확] Redis 보상 로직을 실행하지 않고 예외를 전파합니다. couponId: {}", couponId, e);
                throw e;
            }

            // 확실한 실패(DB_SAVE_FAILED, KAFKA_PUBLISH_FAILED 등) 시에만 멱등한 Redis 보상 실행
            // 동기 DB 저장 실패 에러는 모두 여기서 걸린다.
            log.error(
                    "[쿠폰 저장/발행 확정 실패] Redis 보상 로직을 실행합니다. couponId: {}, reason: {}",
                    couponId,
                    e.getReason());
            rollbackInRedis(stockIdKey, issuedCampaignKey, userId);
            throw e;

        } catch (Exception e) {
            // 기타 예상치 못한 인프라/시스템 예외 발생 시 보상 유예
        	// 카프카 발행 중 처리되지 않은 예외
            log.error("[알 수 없는 인프라 예외 발생] DB/Kafka 저장 상태가 불명확하므로 Redis 보상 없이 UNKNOWN 전파. couponId: {}", couponId, e);
            //rollbackInRedis(stockIdKey, issuedCampaignKey, userId);
            throw new CouponIssueException(IssueFailReason.SYSTEM_ERROR, e);
        }

        // #6. 성공 결과 반환 (IssueResult)
        return IssueResult.success(couponId);
    }

    @Override
    public String name() {
        return "LUA_SCRIPT";
    }

    /** expireAt 조회 헬퍼 메소드 */
    private LocalDateTime getExpireAt(Long campaignId) {
        String key = String.format("campaign:%d:expireAt", campaignId);
        String expireAtStr = redisTemplate.opsForValue().get(key);

        if (expireAtStr == null) {
            throw new CampaignNotFoundException(campaignId, campaignId);
        }

        // 파싱 과정에서 에러 발생 가능 -> 쿠폰 발급 실패 예외에 담아서 전파
        try {
            long expireAtEpochMillis = Long.parseLong(expireAtStr);
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(expireAtEpochMillis), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            throw new CouponIssueException(IssueFailReason.SYSTEM_ERROR, e);
        }
    }
    
    /** Redis 롤백 전용 헬퍼 메소드 (지표 수집 및 반환값 처리) */
    private void rollbackInRedis(String stockIdKey, String issuedCampaignKey, Long userId) {
        try {
            Long result =
                    redisTemplate.execute(
                            rollbackScript,
                            List.of(stockIdKey, issuedCampaignKey),
                            String.valueOf(userId));

            if (result != null && result == 1L) {
                recordCompensationMetric("success");
                log.info("[Redis 보상 성공] 재고 +1 원복 완료. userId: {}", userId);
            } else {
                recordCompensationMetric("noop");
                log.info("[Redis 보상 NOP] 이미 복구되었거나 발급 이력이 없는 유저. userId: {}", userId);
            }
        } catch (Exception e) {
            recordCompensationMetric("failure");
            log.error("[Redis 보상 실패] 보상 스크립트 실행 중 네트워크/인프라 예외 발생. userId: {}", userId, e);
        }
    }
    
    private void recordCompensationMetric(String result) {
        Counter.builder("coupon.redis.compensation")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    private IssueFailReason matchFailReason(long resultCode) {
        return switch ((int) resultCode) {
            case -1 -> IssueFailReason.ALREADY_ISSUED;
            case -2 -> IssueFailReason.OUT_OF_STOCK;
            case -4 -> IssueFailReason.CAMPAIGN_NOT_OPEN;
            default -> IssueFailReason.SYSTEM_ERROR;
        };
    }
}
