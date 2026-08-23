package com.uply.coupon.campaign.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CampaignStatusTest {

    private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 21, 10, 0);
    private static final LocalDateTime EXPIRE_AT = LocalDateTime.of(2026, 8, 31, 23, 59, 59);

    // 오픈 시각 이전이면 SCHEDULED로 판정하는지 검증한다.
    @Test
    void beforeOpenAtIsScheduled() {
        LocalDateTime now = OPEN_AT.minusMinutes(1);

        assertThat(CampaignStatus.of(now, OPEN_AT, EXPIRE_AT)).isEqualTo(CampaignStatus.SCHEDULED);
    }

    // 오픈 시각 정각이면 OPEN으로 판정하는지 검증한다 (openAt 이상이면 OPEN).
    @Test
    void atOpenAtIsOpen() {
        assertThat(CampaignStatus.of(OPEN_AT, OPEN_AT, EXPIRE_AT)).isEqualTo(CampaignStatus.OPEN);
    }

    // 오픈 시각과 만료 시각 사이면 OPEN으로 판정하는지 검증한다.
    @Test
    void betweenOpenAndExpireIsOpen() {
        LocalDateTime now = OPEN_AT.plusDays(1);

        assertThat(CampaignStatus.of(now, OPEN_AT, EXPIRE_AT)).isEqualTo(CampaignStatus.OPEN);
    }

    // 만료 시각 정각 및 이후면 CLOSED로 판정하는지 검증한다 (expireAt 이상이면 CLOSED).
    @Test
    void atOrAfterExpireAtIsClosed() {
        assertThat(CampaignStatus.of(EXPIRE_AT, OPEN_AT, EXPIRE_AT))
                .isEqualTo(CampaignStatus.CLOSED);

        LocalDateTime after = EXPIRE_AT.plusMinutes(1);
        assertThat(CampaignStatus.of(after, OPEN_AT, EXPIRE_AT)).isEqualTo(CampaignStatus.CLOSED);
    }
}
