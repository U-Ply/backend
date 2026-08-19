package com.uply.coupon.coupon.strategy;

import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
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

    private DefaultRedisScript<List> issueScript;

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

        // #1. 번호표(couponId) 사전 생성 (TSID)
        Long couponId = couponIdGenerator.generate();

        // #2. Redis Key 생성
        // stockId key
        String stockIdKey = String.format("stock:%d", stockId);
        // 캠페인 중복 검사 Key: coupon:issued:{campaignId}
        String issuedCampaignKey = String.format("issued:%d", campaignId);
        // 캠페인 오픈 시각 Key
        String campaignOpenAtKey = String.format("campaign:%d:openAt", campaignId);

        // #4. Lua Script 실행 (Atomic 연산)
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

        // #5. 실패인 경우 처리
        if (resultCode != 1) {
            IssueFailReason failReason = matchFailReason(resultCode);
            return IssueResult.fail(failReason);
        }

        // #6. DB 저장 전략 선택 : 동기 / Kafka 비동기
        try {
            couponSaveStrategy.save(couponId, userId, campaignId, stockId, idempotencyKey);

        } catch (Exception e) {
            // MySQL 동기 저장 실패 또는 Kafka 발행 실패 -> Redis 재고 차감 복구
            redisTemplate.execute(
                    new SessionCallback<Object>() {
                        @Override
                        public Object execute(RedisOperations operations) {
                            operations.multi(); // 트랜잭션 시작
                            operations.opsForValue().increment("stock:" + campaignId);
                            operations
                                    .opsForSet()
                                    .remove("issued:" + campaignId, String.valueOf(userId));
                            return operations.exec(); // 원자적 일괄 실행
                        }
                    });

            throw e; // 예외 재전파 -> 서비스 레이어 catch(Exception e) 멱등성 키도 삭제됨
        }

        // #7. 성공 결과 반환 (IssueResult)
        return IssueResult.success(couponId);
    }

    @Override
    public String name() {
        return "LUA_SCRIPT";
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
