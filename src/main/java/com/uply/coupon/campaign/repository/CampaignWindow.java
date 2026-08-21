package com.uply.coupon.campaign.repository;

import java.time.LocalDateTime;

/**
 * 캠페인의 발급 가능 구간(open_at ~ expire_at) 조회용 프로젝션.
 *
 * <p>두 시각을 따로 조회하면 쿼리가 두 번 나가고, 그 사이 캠페인이 수정되면 오픈 판정과 만료 판정이 서로 다른 스냅샷을 보게 된다. 한 번에 읽어 한 판단에 같은 값을
 * 쓰기 위한 타입이다.
 */
public interface CampaignWindow {

    LocalDateTime getOpenAt();

    LocalDateTime getExpireAt();
}
