package com.uply.coupon.campaign.service;

import com.uply.coupon.campaign.domain.CampaignStock;
import com.uply.coupon.campaign.repository.CampaignRepository;
import com.uply.coupon.campaign.repository.CampaignStockRepository;
import com.uply.coupon.common.exception.CacheRecoveryNotSettledException;
import com.uply.coupon.common.exception.CampaignNotFoundException;
import com.uply.coupon.coupon.repository.CouponRepository;
import com.uply.coupon.operation.reconciliation.domain.KafkaSettlement;
import com.uply.coupon.operation.reconciliation.service.KafkaSettlementChecker;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 개시 전 RDB의 캠페인 재고 데이터를 Redis 캐시에 사전 적재(Warm-up) 및 장애 복구하는 서비스
 *
 * <pre>
 * 복구 실행 선행 조건 (Disaster Recovery Prerequisites)
 *   1. 신규 발급 트래픽 차단 (Gateway 수준)
 *   2. Kafka Consumer Lag = 0 및 DLT 처리 완료 확인 (DB의 최종 정합성 보장)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignCacheWarmupService {

    private static final String KEY_STOCK = "stock:%d";
    private static final String KEY_STOCK_ID = "stockId:%d:%s:%s";
    private static final String KEY_CAMPAIGN_OPEN_AT = "campaign:%d:openAt";
    private static final String KEY_CAMPAIGN_EXPIRE_AT = "campaign:%d:expireAt";
    private static final String KEY_ISSUED = "issued:%d";
    private static final String KEY_TEMP_ISSUED = "temp:issued:%d";

    private final CampaignRepository campaignRepository;
    private final CampaignStockRepository campaignStockRepository;
    private final CouponRepository couponRepository;
    private final StringRedisTemplate redisTemplate;

    // V3(coupon.save.strategy=kafka)에서만 빈이 존재한다. V0~V2에서는 비어 있으므로
    // 직접 주입하면 컨텍스트 기동이 깨진다.
    private final ObjectProvider<KafkaSettlementChecker> kafkaSettlementCheckerProvider;

    /**
     * 특정 캠페인의 잔여 재고 및 발급 이력을 RDB에서 조회하여 Redis 캐시에 적재/복구
     *
     * <p><b>실행 전제</b> — 이 메서드는 DB를 정답으로 보고 Redis를 통째로 덮어쓴다. 따라서 발급 요청을 차단하고 Kafka lag이 0인 것(=발급
     * 이벤트가 모두 DB에 반영된 것)을 확인한 뒤에 호출해야 한다. lag이 남은 상태로 호출하면 아직 DB에 없는 발급분이 issued Set에서 사라져 같은 유저가
     * 다시 발급받을 수 있고, 재고도 그만큼 부풀려 복구된다.
     *
     * @param campaignId 사전 적재 및 복구 대상 캠페인 ID
     */
    @Transactional(readOnly = true)
    public void warmupCampaign(Long campaignId) {
        log.info("Starting cache warm-up and recovery for campaignId: {}", campaignId);

        // 0. Kafka 정착 확인 (V3 전용)
        requireKafkaSettled();

        // 1. 해당 캠페인의 전체 Stock 목록 DB 조회
        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
            // 재고 풀이 없는 원인이 "존재하지 않는 캠페인"이면 호출자가 성공으로 오인하면 안 된다.
            // 실제 캠페인인데 재고 풀만 아직 없는 경우에만 조용히 넘어간다.
            if (!campaignRepository.existsById(campaignId)) {
                throw new CampaignNotFoundException(campaignId);
            }
            log.warn("No stocks found for campaignId: {}", campaignId);
            return;
        }

        // 2. 오픈 시각 및 만료 시각 캐싱
        //
        // TTL을 걸지 않는다. 요구분석서 13절 Redis 키 표가 재고와 발급 Set을 TTL 없음으로 규정하고
        // 있고, 고정 24시간을 걸면 캠페인이 그보다 길 때 발급 도중 키가 사라진다. 키를 지우는 것은
        // 회차 초기화 스크립트와 웜업의 책임이지 만료 시간의 책임이 아니다.
        long openAtEpochMillis =
                stocks.get(0).getCampaign().getOpenAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate
                .opsForValue()
                .set(
                        String.format(KEY_CAMPAIGN_OPEN_AT, campaignId),
                        String.valueOf(openAtEpochMillis));

        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate
                .opsForValue()
                .set(
                        String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId),
                        String.valueOf(expireAtEpochMillis));

        // 3. stock:{stockId} 잔여 재고(remainingStock) 기준 캐싱
        for (CampaignStock stock : stocks) {
            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(KEY_STOCK, stock.getId()),
                            String.valueOf(stock.getRemainingStock()));

            redisTemplate
                    .opsForValue()
                    .set(
                            String.format(
                                    KEY_STOCK_ID,
                                    campaignId,
                                    stock.getRouteId(),
                                    stock.getFareClass()),
                            String.valueOf(stock.getId()));
        }

        // 4. issued:{campaignId} Set 원자적 재구축 (RENAME 활용)
        rebuildIssuedSet(campaignId);

        log.info(
                "Cache warm-up and recovery completed successfully for campaignId: {}", campaignId);
    }

    /**
     * Kafka가 정착(lag 0, DLT 0)하지 않았으면 복구를 거부한다.
     *
     * <p>lag이 남은 상태에서 DB를 정답으로 삼아 Redis를 덮어쓰면, 아직 DB에 반영되지 않은 발급분이 issued Set에서 사라져 같은 유저가 다시 발급받을
     * 수 있고 재고도 그만큼 부풀려 복구된다.
     *
     * <p>Checker 빈은 {@code coupon.save.strategy=kafka}일 때만 생성된다. 따라서 V0~V2 회차에서는 빈이 없어 이 검사가
     * 건너뛰어지고, V3 회차에서만 강제된다. Kafka 조회 자체가 실패하면 예외가 그대로 전파되어 웜업이 중단된다.
     */
    private void requireKafkaSettled() {
        KafkaSettlementChecker checker = kafkaSettlementCheckerProvider.getIfAvailable();
        if (checker == null) {
            return;
        }

        KafkaSettlement settlement = checker.check();
        if (!settlement.settled()) {
            throw new CacheRecoveryNotSettledException(
                    "Kafka 미정착 상태에서는 캐시 복구를 실행할 수 없습니다. lag="
                            + settlement.lag()
                            + ", dlt="
                            + settlement.dltCount());
        }
    }

    private void rebuildIssuedSet(Long campaignId) {
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        String tempIssuedKey = String.format(KEY_TEMP_ISSUED, campaignId);

        // 이전 미완료 작업으로 잔존할 수 있는 임시 키 제거
        redisTemplate.delete(tempIssuedKey);

        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);

        if (issuedUserIds == null || issuedUserIds.isEmpty()) {
            // DB에 발급자가 없으면 Set도 비어 있어야 한다.
            // SADD만 하던 기존 방식은 이 경우 아무 일도 하지 않아, Redis에만 남아 있던
            // 오염된 유저가 "복구" 후에도 그대로 살아남았다.
            redisTemplate.delete(issuedKey);
            log.info("Cleared issued Set for campaignId: {} (DB에 발급 이력 없음)", campaignId);
        } else {
            String[] userIdStrs =
                    issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);

            // 임시 Set에 DB 기준 발급 유저를 적재한 뒤 RENAME으로 원자 교체한다.
            // 기존 키에 SADD로 덧칠하면 DB에 없는 유저가 Set에 남아 발급이 부당하게 거부된다.
            redisTemplate.opsForSet().add(tempIssuedKey, userIdStrs);
            redisTemplate.rename(tempIssuedKey, issuedKey);

            log.info(
                    "Atomically replaced issued Set for campaignId: {} with {} users",
                    campaignId,
                    userIdStrs.length);
        }
    }
}
