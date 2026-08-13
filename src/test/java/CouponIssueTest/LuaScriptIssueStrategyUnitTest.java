package CouponIssueTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.coupon.dto.IdempotencyCache;
import com.uply.coupon.coupon.strategy.IssueFailReason;
import com.uply.coupon.coupon.strategy.IssueResult;
import com.uply.coupon.coupon.strategy.LuaScriptIssueStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;

class LuaScriptIssueStrategyUnitTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private KafkaTemplate<String, Object> kafkaTemplate;
    private LuaScriptIssueStrategy luaScriptIssueStrategy;
    private ObjectMapper objectMapper;

    private final Long campaignId = 1L;
    private final Long routeId = 100L;
    private final Long fareClassId = 2L;
    private final Long stockId = 5001L;

    private String mapKey;
    private String issuedKey;
    private String stockKey;

    @BeforeAll
    static void beforeAll() {
        // 로컬 Redis 커넥션 직접 연결
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void afterAll() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // KafkaTemplate Mock 생성 (테스트 시 호출만 되고 검증은 생략)
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper();

        // Strategy 수동 생성 및 스크립트 초기화
        luaScriptIssueStrategy = new LuaScriptIssueStrategy(redisTemplate, kafkaTemplate, objectMapper);
        luaScriptIssueStrategy.init();

        // Redis Key 구성
        mapKey = String.format("stock-map:%d:%d:%d", campaignId, routeId, fareClassId);
        issuedKey = String.format("issued:%d", campaignId);
        stockKey = "stock:" + stockId;

        // 사전 캐시 워밍 (Cache Warm-up)
        redisTemplate.opsForValue().set(mapKey, String.valueOf(stockId));
        redisTemplate.opsForValue().set(stockKey, "2"); // 초기 재고 2개
    }

    @Test
    @DisplayName("정상 요청 시 Redis 재고가 차감되고 유저가 발급 목록에 추가된다")
    void issue_Success() {
        // given
        Long userId = 10L;

        // when (idempotencyKey 파라미터에 빈 문자열 "" 전달)
        IssueResult result = luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, "");

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.couponId()).isNotNull();
        assertThat(result.stockId()).isEqualTo(stockId); // stockId 검증 추가 (5001L)

        // Redis 상태 검증
        String remainingStock = redisTemplate.opsForValue().get(stockKey);
        Boolean isMember = redisTemplate.opsForSet().isMember(issuedKey, String.valueOf(userId));

        assertThat(remainingStock).isEqualTo("1"); // 2 -> 1 차감
        assertThat(isMember).isTrue();            // 발급 목록 유저 존재
    }

    @Test
    @DisplayName("동일 유저 중복 요청 시 ALREADY_ISSUED 실패 결과를 반환한다")
    void issue_Fail_AlreadyIssued() {
        // given
        Long userId = 10L;
        luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, ""); // 1차 발급 성공

        // when (동일 유저 재요청)
        IssueResult result = luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, "");

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.ALREADY_ISSUED);

        // 재고는 1차 발급 때 차감된 1 상태 유지
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("1");
    }

    @Test
    @DisplayName("재고 부족 시 OUT_OF_STOCK 실패 결과를 반환한다")
    void issue_Fail_OutOfStock() {
        // given
        Long user1 = 10L;
        Long user2 = 20L;
        Long user3 = 30L;

        luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, user1, ""); // 재고 1 남음
        luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, user2, ""); // 재고 0 남음

        // when (재고 0 상태에서 요청)
        IssueResult result = luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, user3, "");

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(IssueFailReason.OUT_OF_STOCK);
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("0");
    }
    
    @Test
    @DisplayName("정상 발급 시 멱등성 캐시가 idempotency:issue:{key} 포맷으로 저장되고 TTL은 10분이다")
    void issue_Success_SaveIdempotencyCache() throws Exception {
        // given
        Long userId = 10L;
        String idempotencyKey = "tx-uuid-1234";
        String expectedRedisKey = "idempotency:issue:" + idempotencyKey;

        // when
        IssueResult result = luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, idempotencyKey);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.stockId()).isEqualTo(stockId);

        // 1. Redis에 멱등성 키 존재 여부 검증
        String cachedJson = redisTemplate.opsForValue().get(expectedRedisKey);
        assertThat(cachedJson).isNotNull();

        // 2. JSON 내용 규약 검증 (httpStatus: 200, body 데이터 포함)
        IdempotencyCache cache = objectMapper.readValue(cachedJson, IdempotencyCache.class);
        assertThat(cache.getHttpStatus()).isEqualTo(200);
        assertThat(cache.getBody()).contains(String.valueOf(result.couponId()));
        assertThat(cache.getBody()).contains(String.valueOf(stockId));

        // 3. TTL 검증 (10분 = 600초)
        Long expireTime = redisTemplate.getExpire(expectedRedisKey, TimeUnit.SECONDS);
        assertThat(expireTime).isGreaterThan(0).isLessThanOrEqualTo(600);
    }

    @Test
    @DisplayName("동일한 idempotencyKey로 재요청 시 DUPLICATE_REQUEST 에러를 반환하고 재고를 추가 차감하지 않는다")
    void issue_Fail_DuplicateIdempotencyKey() {
        // given
        Long userId = 10L;
        String idempotencyKey = "tx-uuid-dup-test";

        // 1차 요청 수행 (성공)
        luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, idempotencyKey);

        // when (동일한 idempotencyKey로 2차 요청)
        IssueResult duplicateResult = luaScriptIssueStrategy.issue(campaignId, routeId, fareClassId, userId, idempotencyKey);

        // then
        assertThat(duplicateResult.success()).isFalse();
        assertThat(duplicateResult.reason()).isEqualTo(IssueFailReason.DUPLICATE_REQUEST);

        // 재고는 1차 발급 차감 후인 1 상태 유지 (중복 차감 방지)
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("1");
    }
}