package com.uply.coupon.campaign.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "campaign_id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "open_at")
    private LocalDateTime openAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Campaign(String name, LocalDateTime openAt, LocalDateTime expireAt) {
        this.name = name;
        this.openAt = openAt;
        this.expireAt = expireAt;
        this.createdAt = LocalDateTime.now();
    }
}