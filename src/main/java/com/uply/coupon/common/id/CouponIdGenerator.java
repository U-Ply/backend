package com.uply.coupon.common.id;

import io.hypersistence.tsid.TSID;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 ID 생성기. DB의 AUTO_INCREMENT 대신 애플리케이션이 ID를 만든다. (1-B의 Redis+Kafka 경로에서는 MySQL INSERT 이전에 응답과
 * 이벤트에 couponId가 필요하기 때문) TSID는 시간순 정렬이 가능한 64비트 ID라 BIGINT 컬럼에 그대로 들어간다.
 *
 * <p>라이브러리를 바꾸더라도 이 클래스 한 곳만 고치면 되도록 격리해 둔다.
 */
@Component
public class CouponIdGenerator {

    public long generate() {
        return TSID.Factory.getTsid().toLong();
    }
}
