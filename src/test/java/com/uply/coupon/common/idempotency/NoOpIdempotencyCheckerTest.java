package com.uply.coupon.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class NoOpIdempotencyCheckerTest {

    private final NoOpIdempotencyChecker checker = new NoOpIdempotencyChecker();

    @Test
    void doesNotCacheOrBlockRequests() {
        assertThat(checker.getCachedResponse("key")).isEmpty();
        assertThatCode(() -> checker.cacheResponse("key", "{}", 200)).doesNotThrowAnyException();
        assertThatCode(() -> checker.clearProgress("key")).doesNotThrowAnyException();
    }
}
