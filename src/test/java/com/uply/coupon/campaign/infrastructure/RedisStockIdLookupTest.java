package com.uply.coupon.campaign.infrastructure;

import com.uply.coupon.common.exception.CampaignNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisStockIdLookupTest {

    @InjectMocks
    private RedisStockIdLookup redisStockIdLookup;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("Redis에 stockId 키가 존재하면 Long 타입의 stockId를 정상 반환한다.")
    void lookupStockId_Success() {
        // given
        Long campaignId = 1L;
        String routeId = "ICN-NRT";
        String fareClass = "Y";
        String expectedKey = "stockId:1:ICN-NRT:Y";

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(expectedKey)).willReturn("100");

        // when
        Long stockId = redisStockIdLookup.lookupStockId(campaignId, routeId, fareClass);

        // then
        assertThat(stockId).isEqualTo(100L);
        verify(valueOperations).get(expectedKey);
    }

    @Test
    @DisplayName("Redis에 stockId 키가 없으면(null) CampaignNotFoundException이 발생한다.")
    void lookupStockId_NotFound_ThrowsException() {
        // given
        Long campaignId = 1L;
        String routeId = "ICN-NRT";
        String fareClass = "Y";
        String expectedKey = "stockId:1:ICN-NRT:Y";

        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(expectedKey)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> redisStockIdLookup.lookupStockId(campaignId, routeId, fareClass))
                .isInstanceOf(CampaignNotFoundException.class);

        verify(valueOperations).get(expectedKey);
    }
}