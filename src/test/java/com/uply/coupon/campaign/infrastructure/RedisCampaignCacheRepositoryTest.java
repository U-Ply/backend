package com.uply.coupon.campaign.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.uply.coupon.common.exception.CampaignStockCacheMissException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisCampaignCacheRepositoryTest {

    @InjectMocks private RedisCampaignCacheRepository repository;

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    // stock:{stockId} 키에 값이 있으면 정수로 파싱해 반환하는지 검증한다.
    @Test
    void getRemainingStock_returnsParsedValue() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("stock:10")).willReturn("1548");

        assertThat(repository.getRemainingStock(10L)).isEqualTo(1548);
    }

    // 캐시 키가 없으면(웜업 전 등) 0으로 간주하지 않고 전용 캐시 미스 예외로 구분하는지 검증한다.
    // 값이 잘못된 경우(파싱 실패)와 달리 이 예외는 자동 복구 트리거 대상이다.
    @Test
    void getRemainingStock_missingKeyThrowsCacheMissException() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("stock:10")).willReturn(null);

        assertThatThrownBy(() -> repository.getRemainingStock(10L))
                .isInstanceOf(CampaignStockCacheMissException.class)
                .satisfies(
                        exception ->
                                assertThat(
                                                ((CampaignStockCacheMissException) exception)
                                                        .getStockId())
                                        .isEqualTo(10L));
    }

    // 캐시 값이 정수로 파싱되지 않으면 서버 오류로 처리하는지 검증한다.
    @Test
    void getRemainingStock_invalidValueThrowsIllegalState() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("stock:10")).willReturn("not-a-number");

        assertThatThrownBy(() -> repository.getRemainingStock(10L))
                .isInstanceOf(IllegalStateException.class);
    }
}
