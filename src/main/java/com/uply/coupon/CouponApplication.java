package com.uply.coupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableBatchProcessing 를 붙이면 안 된다.
// Boot 3 에서는 이 어노테이션이 Batch 자동 설정을 꺼버려서
// spring.batch.jdbc.initialize-schema 가 무시되고 BATCH_* 메타 테이블이
// 생성되지 않는다. Job 첫 실행에서 Table doesn't exist 로 죽는다.

@SpringBootApplication
// @EnableBatchProcessing
public class CouponApplication {

    public static void main(String[] args) {
        SpringApplication.run(CouponApplication.class, args);
    }
}
