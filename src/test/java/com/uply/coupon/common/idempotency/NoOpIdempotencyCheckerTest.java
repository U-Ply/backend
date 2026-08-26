package com.uply.coupon.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class NoOpIdempotencyCheckerTest {

    private final NoOpIdempotencyChecker checker = new NoOpIdempotencyChecker();

    @Test
    void doesNotCacheOrBlockRequests() {
        IdempotencyClaim claim = checker.acquire("key", "hash");

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.hasCachedResponse()).isFalse();
        assertThat(claim.ownerToken()).isNotNull();

        assertThatCode(() -> checker.complete("key", claim.ownerToken(), "hash", "{}", 200))
                .doesNotThrowAnyException();
        assertThat(checker.complete("key", claim.ownerToken(), "hash", "{}", 200)).isTrue();

        assertThatCode(() -> checker.release("key", claim.ownerToken())).doesNotThrowAnyException();
        assertThat(checker.release("key", claim.ownerToken())).isTrue();

        assertThat(checker.renew("key", claim.ownerToken())).isTrue();
    }
}
