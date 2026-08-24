package com.uply.coupon.coupon.strategy;

import com.uply.coupon.campaign.domain.Campaign;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.service.CampaignCacheWarmupService;
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
    private final CampaignRepository campaignRepository;
    private final CampaignCacheWarmupService campaignCacheWarmupService;

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
        // 캠페인 만료 시각 Key
        String campaignExpireAtKey = campaignExpireAtKey(campaignId);
        // 캐시 존재 확인용 조회 (미기재 캠페인이면 404 CampaignNotFoundException).
        // 여기서 읽은 값 자체는 버린다 - Lua 실행 직전까지 시간이 있어 그 사이 웜업 복구가
        // 이 키를 덮어썼을 수 있다. DB에 쓰는 값은 Lua가 실제로 판정에 쓴 값(반환값)이어야 한다.
        //getExpireAt(campaignId); // 실패 가능 -> 사전 조회 삭제

        // #3. Lua Script 실행 (Atomic 연산)
        // 오픈/만료 판정도 스크립트 안에서 한다. Java에서 미리 검사하면 검사와 차감 사이에
        // 캠페인이 만료되는 창이 열리고, 그 사이 요청은 만료 후에도 발급에 성공한다.
        List<String> keys = List.of(
					                stockIdKey,
					                issuedCampaignKey,
					                campaignOpenAtKey,
					                campaignExpireAtKey);
        List<Object> result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));

        if (result == null || result.isEmpty()) {
            return IssueResult.fail(IssueFailReason.SYSTEM_ERROR);
        }

        long resultCode = (Long) result.get(0);

        // 웜업 미완료 (Cache Miss)
        if (resultCode == -3) { 
            // 1. DB 조회 (DB에도 없는 캠페인이면 여기서 CampaignNotFoundException(404) 발생 후 즉시 종료)
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new CampaignNotFoundException(campaignId));

            // 2. DB에 존재하므로 재웜업 실행
            campaignCacheWarmupService.warmupCampaign(campaignId);

            // 3. Lua Script 1회 재시도
            result = redisTemplate.execute(issueScript, keys, String.valueOf(userId));
            resultCode = (Long) result.get(0);
        }
        
        // #4. 실패인 경우 처리 (재시도 수행 결과 포함)
        if (resultCode != 1) {
            IssueFailReason failReason = matchFailReason(resultCode);
            return IssueResult.fail(failReason);
        }

        // Lua가 반환한 Redis TIME 기준 발급 시각 (epoch millis)
        LocalDateTime issuedAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli((Long) result.get(1)), ZoneOffset.UTC);
        // Lua가 판정에 실제로 쓴 만료 시각. Java가 위에서 미리 읽은 값과 갈릴 수 있으므로
        // DB에는 이 값을 써야 판정과 저장이 항상 일치한다.
        LocalDateTime expireAt =
                LocalDateTime.ofInstant(Instant.ofEpochMilli((Long) result.get(2)), ZoneOffset.UTC);

        // #5. DB 저장 전략 선택 : 동기 / Kafka 비동기
        try {
            couponSaveStrategy.save(
                    couponId, userId, campaignId, stockId, idempotencyKey, issuedAt, expireAt);

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

            // 보상이 실패하면 Redis 재고가 덜 복구된 상태로 남는다. 이때 원래 실패 사유를 그대로
            // 올려보내면 상위 계층이 "확정 실패"로 보고 멱등성 진행 키를 지워 재시도를 허용한다.
            // 재고는 이미 빠져 있으므로 재시도가 반복될수록 재고만 줄어든다.
            // 그래서 보상 실패는 결과 불명확으로 승격해 진행 키를 유지시킨다.
            if (!rollbackInRedis(stockIdKey, issuedCampaignKey, userId)) {
                throw new CouponIssueException(IssueFailReason.SAVE_RESULT_UNKNOWN, e);
            }
            throw e;

        } catch (Exception e) {
            // 기타 예상치 못한 인프라/시스템 예외 발생 시 보상 유예
            // 카프카 발행 중 처리되지 않은 예외
            log.error(
                    "[알 수 없는 인프라 예외 발생] DB/Kafka 저장 상태가 불명확하므로 Redis 보상 없이 UNKNOWN 전파. couponId: {}",
                    couponId,
                    e);
            // rollbackInRedis(stockIdKey, issuedCampaignKey, userId);
            throw new CouponIssueException(IssueFailReason.SYSTEM_ERROR, e);
        }

        // #6. 성공 결과 반환 (IssueResult)
        return IssueResult.success(
                couponId, issuedAt.toInstant(ZoneOffset.UTC), expireAt.toInstant(ZoneOffset.UTC));
    }

    @Override
    public String name() {
        return "LUA_SCRIPT";
    }

    private String campaignExpireAtKey(Long campaignId) {
        return String.format("campaign:%d:expireAt", campaignId);
    }

    /** expireAt 조회 헬퍼 메소드 */
    private LocalDateTime getExpireAt(Long campaignId) {
        String expireAtStr = redisTemplate.opsForValue().get(campaignExpireAtKey(campaignId));

        if (expireAtStr == null) {
            // 캐시 누락이므로 재고 풀이 아니라 캠페인 단위 예외다 (stockId를 아는 상황이 아니다)
            throw new CampaignNotFoundException(campaignId);
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

    /**
     * Redis 재고·발급 Set을 되돌린다.
     *
     * @return 보상이 반영됐거나(SUCCESS) 이미 반영돼 있으면(NOOP) true, 실행 자체가 실패하면 false. 호출자는 false일 때 재고가 덜 복구된
     *     상태임을 전제로 처리해야 한다.
     */
    private boolean rollbackInRedis(String stockIdKey, String issuedCampaignKey, Long userId) {
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
            return true;
        } catch (Exception e) {
            recordCompensationMetric("failure");
            log.error("[Redis 보상 실패] 보상 스크립트 실행 중 네트워크/인프라 예외 발생. userId: {}", userId, e);
            return false;
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
            case -3 -> IssueFailReason.CAMPAIGN_NOT_CACHED;
            case -4 -> IssueFailReason.CAMPAIGN_NOT_OPEN;
            case -5 -> IssueFailReason.CAMPAIGN_EXPIRED;
            default -> IssueFailReason.SYSTEM_ERROR;
        };
    }
}
