package com.uply.coupon.campaign.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "campaign_stocks",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_campaign_route_fare",
            columnNames = {"campaign_id", "route_id", "fare_class"}
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
}