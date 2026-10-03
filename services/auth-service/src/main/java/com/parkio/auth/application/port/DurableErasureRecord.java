package com.parkio.auth.application.port;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import java.time.Instant;
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

    /**
     * The {@code bodyDigest} of the durable evidence format, which the Python verifier checks:
     * SHA-256 of the canonical {@code {authUserId, erasureRequestId, erasedAt}} JSON.
     */
    public static String digest(UUID erasureRequestId, UUID authUserId, Instant erasedAt) {
        return DurableErasureEvidence.bodyDigest(
                authUserId, erasureRequestId, DurableErasureEvidence.erasedAt(erasedAt));
    }
}
