package com.uply.coupon.campaign.domain;


import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign_stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 외부에서 CampaignStock을 생성못하도록 설정
public class CampaignStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "route_id", nullable = false, length = 50)
    private String routeId;

    @Column(name = "fare_class", nullable = false, length = 20)
    private String fareClass;

    @Column(name = "total_stock", nullable = false, updatable = false)
    private Integer totalStock;

    @Column(name = "remaining_stock", nullable = false)
    private Integer remainingStock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public CampaignStock(Campaign campaign, String routeId, String fareClass, Integer totalStock) {
        this.campaign = campaign;
        this.routeId = routeId;
        this.fareClass = fareClass;
        this.totalStock = totalStock;
        this.remainingStock = totalStock;
        this.createdAt = LocalDateTime.now();
    }

    // 도메인 비즈니스 메서드 (객체지향적 상태 변경)
    public void decreaseStock(int quantity) {
        if (this.remainingStock - quantity < 0) {
            throw new IllegalStateException("재고가 부족합니다.");
        }
        this.remainingStock -= quantity;
    }

    public void increaseStock(int quantity) {
        if (this.remainingStock + quantity > this.totalStock) {
            throw new IllegalStateException("총 재고 수량을 초과할 수 없습니다.");
        }
        this.remainingStock += quantity;
    }

    // 재고 차감
    // Setter를 하지 않고 이 메서드로 값을 변경하기 때문에 재고를 바꾼 이유를 알 수 있음.
    public void decrease() {
        this.remainingStock -= 1;
    }
    
    public void increase() {
    	this.remainingStock += 1;
    }
}
