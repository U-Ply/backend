package com.uply.coupon.coupon.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.uply.coupon.common.id.CouponIdGenerator;
import com.uply.coupon.coupon.strategy.save.CouponSaveStrategy;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class LuaScriptIssueStrategyUnitTest {

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private CouponIdGenerator couponIdGenerator;

    @Mock private CouponSaveStrategy couponSaveStrategy;

    @InjectMocks private LuaScriptIssueStrategy luaScriptIssueStrategy;

    @BeforeEach
    void setUp() {
        luaScriptIssueStrategy.init();
    }

    @Test
    @DisplayName("Lua Script 실행 성공(1) 시 DB 저장 전략을 호출하고 성공 결과를 반환한다")
    void issue_Success() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(1L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isEqualTo(couponId);

        // 저장 전략 save() 정상 호출 검증
        verify(couponSaveStrategy).save(couponId, userId, campaignId, stockId, idempotencyKey);
    }

    @Test
    @DisplayName("이미 발급된 유저(-1)인 경우 저장 전략을 호출하지 않고 ALREADY_ISSUED를 반환한다")
    void issue_Fail_AlreadyIssued() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-1L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.ALREADY_ISSUED);

        // 실패 시 저장 전략 미호출 검증
        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재고가 부족한 경우(-2) 저장 전략을 호출하지 않고 OUT_OF_STOCK을 반환한다")
    void issue_Fail_OutOfStock() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-2L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.OUT_OF_STOCK);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("캠페인 오픈 전인 경우(-4) 저장 전략을 호출하지 않고 CAMPAIGN_NOT_OPEN을 반환한다")
    void issue_Fail_CampaignNotOpen() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-4L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.CAMPAIGN_NOT_OPEN);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Redis 응답이 빈 리스트인 경우 저장 전략을 호출하지 않고 SYSTEM_ERROR를 반환한다")
    void issue_Fail_EmptyRedisResult() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(Collections.emptyList());

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.SYSTEM_ERROR);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("정의되지 않은 스크립트 반환 코드(-99)인 경우 SYSTEM_ERROR를 반환한다")
    void issue_Fail_UnknownResultCode() {
        // given
        Long campaignId = 1L;
        Long userId = 100L;
        Long stockId = 10L;
        Long couponId = 1000L;
        String idempotencyKey = "idempotency-key-123";

        given(couponIdGenerator.generate()).willReturn(couponId);
        given(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .willReturn(List.of(-99L));

        // when
        IssueResult result =
                luaScriptIssueStrategy.issue(campaignId, userId, stockId, idempotencyKey);

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.SYSTEM_ERROR);

        verify(couponSaveStrategy, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("전략 이름을 조회하면 LUA_SCRIPT를 반환한다")
    void name_Success() {
        // when
        String name = luaScriptIssueStrategy.name();

        // then
        assertThat(name).isEqualTo("LUA_SCRIPT");
    }
}
