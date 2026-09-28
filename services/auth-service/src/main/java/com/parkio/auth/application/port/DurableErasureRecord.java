package com.parkio.auth.application.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable erasure recording. Identity is the request id; retries reuse it.
 * This is auth-side evidence, not an off-host WORM certificate.
 */
public record DurableErasureRecord(
        UUID erasureRequestId,
        UUID authUserId,
        Instant erasedAt,
        String bodyDigest) {

    public DurableErasureRecord {
        Objects.requireNonNull(erasureRequestId, "erasureRequestId");
        Objects.requireNonNull(authUserId, "authUserId");
        Objects.requireNonNull(erasedAt, "erasedAt");
        Objects.requireNonNull(bodyDigest, "bodyDigest");
    }

    public static DurableErasureRecord of(UUID erasureRequestId, UUID authUserId, Instant erasedAt) {
        return new DurableErasureRecord(
                erasureRequestId, authUserId, erasedAt, digest(erasureRequestId, authUserId, erasedAt));
    }

    public static String digest(UUID erasureRequestId, UUID authUserId, Instant erasedAt) {
        String canonical = erasureRequestId + "\n" + authUserId + "\n" + erasedAt;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
