package com.uply.coupon.common.exception;

/**
 * Redis {@code stock:{stockId}} 키 자체가 없는 경우(캐시 미준비/유실) 전용 예외. 값이 잘못된 경우(파싱 실패)는 시스템 오류로 별도 처리한다.
 */
public class CampaignStockCacheMissException extends RuntimeException {

    private final Long stockId;

    public CampaignStockCacheMissException(Long stockId) {
        super("Remaining stock cache not ready: stockId=" + stockId);
        this.stockId = stockId;
    }

    public Long getStockId() {
        return stockId;
    }
}
