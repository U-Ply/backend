package com.uply.coupon.common.idempotency;

import com.uply.coupon.common.exception.InvalidIdempotencyKeyException;
import java.util.UUID;

public final class IdempotencyKeyValidator {

    private IdempotencyKeyValidator() {}

    public static void validateUuidV4(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }

        try {
            UUID uuid = UUID.fromString(idempotencyKey);
            boolean canonicalFormat = uuid.toString().equalsIgnoreCase(idempotencyKey);
            boolean uuidV4 = uuid.version() == 4 && uuid.variant() == 2;
            if (!canonicalFormat || !uuidV4) {
                throw new InvalidIdempotencyKeyException();
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
