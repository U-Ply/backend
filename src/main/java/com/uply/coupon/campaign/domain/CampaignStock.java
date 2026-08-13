package com.uply.coupon.campaign.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign_stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 외부에서 CampaignStock을 생성못하도록 설정
public class CampaignStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long stockId;

    private Long campaignId;
    private String routeId;
    private String fareClass;

    private int totalStock;
    private int remainingStock;

    // 재고 차감
    // Setter를 하지 않고 이 메서드로 값을 변경하기 때문에 재고를 바꾼 이유를 알 수 있음.
    public void decrease() {
        this.remainingStock -= 1;
    }
}
