package com.parkio.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A refresh token belonging to a user. The domain only ever holds the token's
 * hash — the raw value is never stored (ai-context/07).
 */
public final class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private final UUID tokenFamilyId;
    private final Instant familyStartedAt;
    private final UUID parentTokenId;
    private boolean revoked;
    private boolean reusedDetected;
    private RefreshTokenRevocationReason revokedReason;
    private Instant revokedAt;
    private final Long version;

    public RefreshToken(UUID id,
                        UUID userId,
                        String tokenHash,
                        Instant expiresAt,
                        UUID tokenFamilyId,
                        Instant familyStartedAt,
                        UUID parentTokenId,
                        boolean revoked,
                        boolean reusedDetected,
                        RefreshTokenRevocationReason revokedReason,
                        Instant revokedAt,
                        Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.tokenFamilyId = Objects.requireNonNull(tokenFamilyId, "tokenFamilyId");
        this.familyStartedAt = Objects.requireNonNull(familyStartedAt, "familyStartedAt");
        this.parentTokenId = parentTokenId;
        this.revoked = revoked;
        this.reusedDetected = reusedDetected;
        this.revokedReason = revokedReason;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    /**
     * Issues the root token for a new login/session family. {@code familyStartedAt}
     * stamps the family's absolute-lifetime clock; every rotated child inherits it.
     */
    public static RefreshToken issueRoot(UUID userId, String tokenHash, Instant expiresAt, Instant familyStartedAt) {
        return new RefreshToken(
                UUID.randomUUID(),
                userId,
                tokenHash,
                expiresAt,
                UUID.randomUUID(),
                familyStartedAt,
                null,
                false,
                false,
                null,
                null,
                null);
    }

    /**
     * Issues a rotated child while retaining the original session family — including
     * its {@code familyStartedAt}, so rotation never extends the absolute lifetime.
     */
    public static RefreshToken issueChild(RefreshToken parent, String tokenHash, Instant expiresAt) {
        Objects.requireNonNull(parent, "parent");
        return new RefreshToken(
                UUID.randomUUID(),
                parent.userId,
                tokenHash,
                expiresAt,
                parent.tokenFamilyId,
                parent.familyStartedAt,
                parent.id,
                false,
                false,
                null,
                null,
                null);
    }

    public boolean isActive(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * True once the token's family has outlived the absolute session lifetime cap
     * ({@code familyStartedAt + absoluteTtl}). Independent of the sliding per-token
     * expiry: a family is force-expired even while individual tokens are still fresh.
     */
    public boolean isFamilyAbsoluteLifetimeExceeded(Instant now, java.time.Duration absoluteTtl) {
        return now.isAfter(familyStartedAt.plus(absoluteTtl));
    }

    public void revoke(RefreshTokenRevocationReason reason, Instant now) {
        if (revoked) {
            return;
        }
        this.revoked = true;
        this.revokedReason = Objects.requireNonNull(reason, "reason");
        this.revokedAt = Objects.requireNonNull(now, "now");
    }

    public void markReuseDetected() {
        this.reusedDetected = true;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public UUID tokenFamilyId() {
        return tokenFamilyId;
    }

    public Instant familyStartedAt() {
        return familyStartedAt;
    }

    public UUID parentTokenId() {
        return parentTokenId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isReusedDetected() {
        return reusedDetected;
    }

    public RefreshTokenRevocationReason revokedReason() {
        return revokedReason;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Long version() {
        return version;
    }
}
