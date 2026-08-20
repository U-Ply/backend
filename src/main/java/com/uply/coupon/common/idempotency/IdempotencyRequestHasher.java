package com.uply.coupon.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class IdempotencyRequestHasher {

    private IdempotencyRequestHasher() {}

    public static String sha256(String method, String normalizedUri, String normalizedBody) {
        String canonicalRequest =
                method.toUpperCase()
                        + "\n"
                        + normalizedUri
                        + "\n"
                        + (normalizedBody == null ? "" : normalizedBody);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available.", exception);
        }
    }
}
