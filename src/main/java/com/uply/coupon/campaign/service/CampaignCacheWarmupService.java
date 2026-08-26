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
import java.util.ArrayList;
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
     * 운영 중 Redis 키 일부가 유실됐을 때, 살아있는 키는 절대 건드리지 않고 없는 키만 채운다.
     *
     * <p>{@link #warmupCampaign}과 달리 트래픽 차단을 전제하지 않는다. openAt/expireAt/stock/stockId 매핑은
     * SETNX({@link org.springframework.data.redis.core.ValueOperations#setIfAbsent})로만 채우므로, 실시간으로
     * 감소 중인 {@code stock:{stockId}}가 DB 스냅샷 값으로 되돌아가 초과 발급을 유발하는 일이 없다. {@code
     * issued:{campaignId}}도 키 자체가 없을 때만 DB 기준으로 채우며, 이미 있으면(빈 캠페인이라 회원이 0명인 정상 상태 포함) 손대지 않는다.
     *
     * <p>복구 직후, 이번에 복구한 캠페인의 재고풀만 대상으로 Redis {@code stock:{stockId}} 값과 DB {@code remainingStock}을
     * 비교하는 가벼운 자체 점검을 수행한다. 시스템 전체를 훑는 REC-01({@code stockReconcileJob})은 여기서 트리거하지 않는다 — REC-01은
     * {@code campaign_stocks} 테이블을 캠페인 조건 없이 전수 스캔하도록 설계돼 있어({@link
     * com.uply.coupon.operation.reconciliation.service.RedisStockReconcileRunner}), 이 메서드 안에서 부르면
     * 지금 복구한 캠페인과 무관한 다른 캠페인의 기존 불일치까지 이 호출의 성패에 섞여 들어간다. 시스템 전체 정합성은 기존 REC-01 스케줄러·수동 배치를 그대로 쓴다.
     *
     * <p>이 자체 점검은 트래픽 진행 중의 정상적인 시간차와 진짜 위험을 구분하려고 단순 불일치가 아니라 방향성으로 판정한다 — 상세 기준은 {@link
     * #verifyRecoveredStocks} 참고. SETNX가 "이미 있어서" 건드리지 않은 키에 재고를 부풀리는 방향의 잘못된 값이 들어있는 경우를 잡아내며,
     * 발견되면 로그와 응답으로 알리고 실제 수정은 운영자가 {@link #warmupCampaign}(트래픽 차단 후 전체 재구축)으로 판단하게 한다.
     *
     * @param campaignId 복구 대상 캠페인 ID
     * @return 위험 신호 상세 목록. 비어 있으면 이번에 복구한 캠페인의 재고풀 중 재고가 부풀려진 것으로 보이는 항목이 없다는 뜻이다(정상 시간차는 보고하지
     *     않는다).
     */
    @Transactional(readOnly = true)
    public List<String> recoverMissingCache(Long campaignId) {
        log.info("Starting selective cache recovery for campaignId: {}", campaignId);

        requireKafkaSettled();

        List<CampaignStock> stocks = campaignStockRepository.findAllByCampaignId(campaignId);
        if (stocks.isEmpty()) {
            if (!campaignRepository.existsById(campaignId)) {
                throw new CampaignNotFoundException(campaignId);
            }
            log.warn("No stocks found for campaignId: {}", campaignId);
            return List.of();
        }

        long openAtEpochMillis =
                stocks.get(0).getCampaign().getOpenAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        long expireAtEpochMillis =
                stocks.get(0).getCampaign().getExpireAt().toInstant(ZoneOffset.UTC).toEpochMilli();

        setIfAbsent(
                String.format(KEY_CAMPAIGN_OPEN_AT, campaignId), String.valueOf(openAtEpochMillis));
        setIfAbsent(
                String.format(KEY_CAMPAIGN_EXPIRE_AT, campaignId),
                String.valueOf(expireAtEpochMillis));

        for (CampaignStock stock : stocks) {
            setIfAbsent(
                    String.format(KEY_STOCK, stock.getId()),
                    String.valueOf(stock.getRemainingStock()));
            setIfAbsent(
                    String.format(
                            KEY_STOCK_ID, campaignId, stock.getRouteId(), stock.getFareClass()),
                    String.valueOf(stock.getId()));
        }

        rebuildIssuedSetIfMissing(campaignId);

        List<String> mismatches = verifyRecoveredStocks(stocks);
        if (mismatches.isEmpty()) {
            log.info("Post-recovery consistency check passed for campaignId: {}", campaignId);
        } else {
            log.warn(
                    "Post-recovery consistency check found {} mismatch(es) for campaignId {}: {}",
                    mismatches.size(),
                    campaignId,
                    mismatches);
        }

        log.info("Selective cache recovery completed for campaignId: {}", campaignId);
        return mismatches;
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

    /** SETNX. 키가 이미 있으면 손대지 않고 false를 반환한다 — {@link #recoverMissingCache} 전용. */
    private boolean setIfAbsent(String key, String value) {
        Boolean applied = redisTemplate.opsForValue().setIfAbsent(key, value);
        return Boolean.TRUE.equals(applied);
    }

    /**
     * {@code issued:{campaignId}} 키가 이미 존재하면(회원 0명인 정상 상태 포함) 아무것도 하지 않는다. 키가 아예 없을 때만 DB 기준으로 채운다
     * — {@link #rebuildIssuedSet}과 달리 기존 멤버를 절대 지우지 않는다(고스트 유저 청소는 {@link #warmupCampaign}의 책임으로
     * 남겨둔다).
     */
    private void rebuildIssuedSetIfMissing(Long campaignId) {
        String issuedKey = String.format(KEY_ISSUED, campaignId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(issuedKey))) {
            return;
        }

        List<Long> issuedUserIds = couponRepository.findUserIdsByCampaignId(campaignId);
        if (issuedUserIds == null || issuedUserIds.isEmpty()) {
            // DB에도 발급자가 없다 — 키를 새로 만들 필요가 없다(빈 Set은 Redis에 존재할 수 없다).
            return;
        }

        String[] userIdStrs = issuedUserIds.stream().map(String::valueOf).toArray(String[]::new);
        redisTemplate.opsForSet().add(issuedKey, userIdStrs);
        log.info(
                "Rebuilt missing issued Set for campaignId: {} with {} users",
                campaignId,
                userIdStrs.length);
    }

    /**
     * 지금 복구한 캠페인의 재고풀만 대상으로 Redis {@code stock:{stockId}}와 DB {@code remainingStock}을 비교한다.
     *
     * <p>REC-01({@link
     * com.uply.coupon.operation.reconciliation.service.RedisStockReconcileRunner})과 판정 기준이 다르다 —
     * REC-01은 lag=0으로 정착된(트래픽이 멈춘) 시점의 정확한 일치를 본다. 이 메서드는 {@link #recoverMissingCache}가 트래픽 차단을
     * 전제하지 않으므로, 메서드 시작 시점에 읽은 DB 스냅샷과 끝난 시점의 Redis 현재값 사이에 정상적인 시간차가 항상 존재한다 — 그동안 Lua가 계속 {@code
     * stock:{stockId}}를 깎았을 수 있기 때문이다. 그래서 단순 불일치({@code redis != db})가 아니라 방향성으로 판정한다: {@code
     * redis <= db}는 Redis가 DB보다 더(또는 같게) 진행된 정상 상태이므로 무시하고, {@code redis > db}(재고가 DB보다 더 많이 남은 것처럼
     * 되돌아간 경우)만 보고한다 — 이 방향만 초과 발급으로 이어질 수 있는 진짜 위험 신호다.
     */
    private List<String> verifyRecoveredStocks(List<CampaignStock> stocks) {
        List<String> keys =
                stocks.stream().map(stock -> String.format(KEY_STOCK, stock.getId())).toList();
        List<String> redisValues =
                keys.isEmpty() ? List.of() : redisTemplate.opsForValue().multiGet(keys);

        // 이 점검은 보조 안전장치일 뿐이다. multiGet이 예상과 다른 행 수를 반환하면(Redis 장애
        // 등) 이미 성공한 SETNX 복구까지 예외로 무너뜨리지 않고, 점검만 건너뛴다.
        if (redisValues == null || redisValues.size() != stocks.size()) {
            log.warn(
                    "Post-recovery consistency check skipped — multiGet returned {} rows for {}"
                            + " stocks",
                    redisValues == null ? "null" : redisValues.size(),
                    stocks.size());
            return List.of();
        }

        List<String> mismatches = new ArrayList<>();
        for (int index = 0; index < stocks.size(); index++) {
            CampaignStock stock = stocks.get(index);
            String redisValue = redisValues.get(index);
            int dbRemaining = stock.getRemainingStock();

            if (redisValue == null) {
                mismatches.add("stockId=" + stock.getId() + " redis=MISSING db=" + dbRemaining);
                continue;
            }

            try {
                int redisRemaining = Integer.parseInt(redisValue);
                if (redisRemaining > dbRemaining) {
                    mismatches.add(
                            "stockId="
                                    + stock.getId()
                                    + " redis="
                                    + redisRemaining
                                    + " db="
                                    + dbRemaining
                                    + " (redis > db, 초과 발급 위험)");
                }
            } catch (NumberFormatException e) {
                mismatches.add(
                        "stockId="
                                + stock.getId()
                                + " redis=INVALID("
                                + redisValue
                                + ") db="
                                + dbRemaining);
            }
        }
        return mismatches;
    }
}
