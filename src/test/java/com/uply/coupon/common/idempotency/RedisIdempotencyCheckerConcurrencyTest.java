package com.uply.coupon.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uply.coupon.common.exception.IdempotencyKeyReusedException;
import com.uply.coupon.common.exception.IdempotencyRequestInProgressException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * ownerToken 기반 Redis Lua CAS(acquire/complete/release/renew)가 실제 Redis에서 원자적으로 동작하는지 검증한다.
 *
 * <p>"TTL 만료 후 다른 요청이 같은 키를 새로 선점"하는 상황은 실제로 30초를 기다리는 대신, Redis 값을 직접 덮어써 재현한다 - Lua는 저장된
 * ownerToken/status/requestHash만 비교하므로, 그 값이 실제 TTL 만료를 거쳐 만들어졌든 테스트에서 직접 만들었든 CAS 판정 결과는 동일하다.
 */
class RedisIdempotencyCheckerConcurrencyTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4.10").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisIdempotencyChecker checker;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        checker = new RedisIdempotencyChecker(redisTemplate, new ObjectMapper());
        checker.init();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private String key() {
        return "key-" + java.util.UUID.randomUUID();
    }

    // A가 선점 후 실패 처리 없이 그대로 시간이 지나 B가 같은 키를 새로 선점했다고 가정한다.
    // (TTL 만료를 기다리는 대신, A의 PROCESSING을 B의 새 PROCESSING으로 직접 덮어써 재현한다.)
    private String simulateOtherOwnerReacquired(String idempotencyKey, String requestHash) {
        IdempotencyClaim bClaim = checker.acquire(idempotencyKey, requestHash);
        assertThat(bClaim.acquired()).isTrue();
        return bClaim.ownerToken();
    }

    @Test
    @DisplayName("A 선점 후 TTL 만료, B 선점, A release → B의 PROCESSING이 유지된다")
    void release_afterOtherOwnerReacquired_keepsNewOwnersProcessing() {
        String key = key();
        String hash = "hash-1";
        IdempotencyClaim aClaim = checker.acquire(key, hash);
        String ownerTokenA = aClaim.ownerToken();

        redisTemplate.delete("idempotency:" + key); // TTL 만료 재현
        String ownerTokenB = simulateOtherOwnerReacquired(key, hash);

        boolean released = checker.release(key, ownerTokenA);

        assertThat(released).isFalse();
        assertThatThrownBy(() -> checker.acquire(key, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        assertThat(ownerTokenA).isNotEqualTo(ownerTokenB);
    }

    @Test
    @DisplayName("A 선점 후 TTL 만료, B 선점, A complete → B의 PROCESSING이 유지된다")
    void complete_afterOtherOwnerReacquired_keepsNewOwnersProcessing() {
        String key = key();
        String hash = "hash-1";
        IdempotencyClaim aClaim = checker.acquire(key, hash);
        String ownerTokenA = aClaim.ownerToken();

        redisTemplate.delete("idempotency:" + key);
        simulateOtherOwnerReacquired(key, hash);

        boolean completed = checker.complete(key, ownerTokenA, hash, "{\"stale\":true}", 200);

        assertThat(completed).isFalse();
        // B의 PROCESSING이 A의 COMPLETED로 훼손되지 않았어야 한다 - 여전히 처리 중으로 보인다.
        assertThatThrownBy(() -> checker.acquire(key, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    @DisplayName("B가 자기 ownerToken으로 complete하면 COMPLETED로 저장되고 TTL이 10분으로 설정된다")
    void complete_withOwnToken_savesCompletedWithTenMinuteTtl() {
        String key = key();
        String hash = "hash-1";
        IdempotencyClaim claim = checker.acquire(key, hash);

        boolean completed =
                checker.complete(key, claim.ownerToken(), hash, "{\"couponId\":\"1\"}", 200);

        assertThat(completed).isTrue();
        Long ttlSeconds = redisTemplate.getExpire("idempotency:" + key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isGreaterThan(Duration.ofMinutes(9).toSeconds());
        assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());

        IdempotencyClaim replay = checker.acquire(key, hash);
        assertThat(replay.hasCachedResponse()).isTrue();
        assertThat(replay.cachedResponse()).isEqualTo("{\"couponId\":\"1\"}");
    }

    @Test
    @DisplayName("같은 ownerToken으로 release하면 키가 삭제된다")
    void release_withMatchingOwnerToken_deletesKey() {
        String key = key();
        IdempotencyClaim claim = checker.acquire(key, "hash-1");

        boolean released = checker.release(key, claim.ownerToken());

        assertThat(released).isTrue();
        assertThat(redisTemplate.hasKey("idempotency:" + key)).isFalse();
    }

    @Test
    @DisplayName("다른 ownerToken으로 release하면 키가 유지된다")
    void release_withMismatchedOwnerToken_keepsKey() {
        String key = key();
        checker.acquire(key, "hash-1");

        boolean released = checker.release(key, "someone-elses-token");

        assertThat(released).isFalse();
        assertThat(redisTemplate.hasKey("idempotency:" + key)).isTrue();
    }

    @Test
    @DisplayName("다른 ownerToken으로 complete하면 기존 PROCESSING 값이 유지된다")
    void complete_withMismatchedOwnerToken_keepsExistingValue() {
        String key = key();
        String hash = "hash-1";
        checker.acquire(key, hash);

        boolean completed = checker.complete(key, "someone-elses-token", hash, "{}", 200);

        assertThat(completed).isFalse();
        assertThatThrownBy(() -> checker.acquire(key, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    @DisplayName("requestHash가 다른 complete는 기존 PROCESSING 값을 유지한다")
    void complete_withMismatchedRequestHash_keepsExistingValue() {
        String key = key();
        String hash = "hash-1";
        IdempotencyClaim claim = checker.acquire(key, hash);

        boolean completed = checker.complete(key, claim.ownerToken(), "different-hash", "{}", 200);

        assertThat(completed).isFalse();
        assertThatThrownBy(() -> checker.acquire(key, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    @DisplayName("올바른 COMPLETED와 동일 hash면 최초 응답을 재반환한다")
    void acquire_completedWithSameHash_returnsCachedResponse() {
        String key = key();
        String hash = "hash-1";
        IdempotencyClaim claim = checker.acquire(key, hash);
        checker.complete(key, claim.ownerToken(), hash, "{\"body\":true}", 200);

        IdempotencyClaim replay = checker.acquire(key, hash);

        assertThat(replay.hasCachedResponse()).isTrue();
        assertThat(replay.cachedResponse()).isEqualTo("{\"body\":true}");
    }

    @Test
    @DisplayName("동일 키를 다른 hash로 재사용하면 IDEMPOTENCY_KEY_REUSED로 차단한다")
    void acquire_sameKeyDifferentHash_throwsKeyReused() {
        String key = key();
        IdempotencyClaim claim = checker.acquire(key, "hash-1");
        checker.complete(key, claim.ownerToken(), "hash-1", "{}", 200);

        assertThatThrownBy(() -> checker.acquire(key, "hash-2"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("COMPLETED인데 body가 없으면 비즈니스 로직 재실행을 차단한다")
    void acquire_completedWithoutBody_blocksReexecution() {
        String key = key();
        String hash = "hash-1";
        // 비정상 캐시 데이터를 직접 주입한다: COMPLETED인데 body가 없음.
        redisTemplate
                .opsForValue()
                .set(
                        "idempotency:" + key,
                        "{\"status\":\"COMPLETED\",\"requestHash\":\"" + hash + "\"}");

        assertThatThrownBy(() -> checker.acquire(key, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    @DisplayName("알 수 없는 status나 잘못된 JSON이면 비즈니스 로직 재실행을 차단한다")
    void acquire_unknownStatusOrInvalidJson_blocksReexecution() {
        String unknownStatusKey = key();
        String hash = "hash-1";
        redisTemplate
                .opsForValue()
                .set(
                        "idempotency:" + unknownStatusKey,
                        "{\"status\":\"WEIRD\",\"requestHash\":\"" + hash + "\"}");
        assertThatThrownBy(() -> checker.acquire(unknownStatusKey, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        String invalidJsonKey = key();
        redisTemplate.opsForValue().set("idempotency:" + invalidJsonKey, "not-json-at-all");
        assertThatThrownBy(() -> checker.acquire(invalidJsonKey, hash))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
    }

    @Test
    @DisplayName("lease 갱신은 올바른 owner만 TTL을 연장할 수 있다")
    void renew_onlyCorrectOwnerExtendsTtl() {
        String key = key();
        IdempotencyClaim claim = checker.acquire(key, "hash-1");

        boolean wrongOwnerRenewed = checker.renew(key, "someone-elses-token");
        assertThat(wrongOwnerRenewed).isFalse();

        boolean correctOwnerRenewed = checker.renew(key, claim.ownerToken());
        assertThat(correctOwnerRenewed).isTrue();

        Long ttlSeconds = redisTemplate.getExpire("idempotency:" + key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isGreaterThan(20L);
        assertThat(ttlSeconds).isLessThanOrEqualTo(30L);
    }

    @Test
    @DisplayName("동시 요청 중 정확히 하나만 PROCESSING을 선점한다(상태 변경 이력 1건)")
    void acquire_concurrentRequests_exactlyOneWinsClaim() throws Exception {
        String key = key();
        String hash = "hash-1";
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<IdempotencyClaim> winners = new CopyOnWriteArrayList<>();
        List<Exception> blocked = new CopyOnWriteArrayList<>();

        try {
            List<java.util.concurrent.Future<?>> futures =
                    IntStream.range(0, threadCount)
                            .mapToObj(
                                    i ->
                                            executor.submit(
                                                    () -> {
                                                        try {
                                                            barrier.await(5, TimeUnit.SECONDS);
                                                            IdempotencyClaim claim =
                                                                    checker.acquire(key, hash);
                                                            winners.add(claim);
                                                        } catch (Exception e) {
                                                            blocked.add(e);
                                                        }
                                                    }))
                            .collect(Collectors.toList());
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(winners).hasSize(1);
        assertThat(blocked).hasSize(threadCount - 1);
        Set<Class<?>> blockedTypes =
                blocked.stream().map(Object::getClass).collect(Collectors.toSet());
        assertThat(blockedTypes).containsExactly(IdempotencyRequestInProgressException.class);
    }
}
