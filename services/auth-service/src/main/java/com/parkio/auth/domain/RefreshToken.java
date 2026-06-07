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
    private boolean revoked;

    public RefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, boolean revoked) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.revoked = revoked;
    }

    /** Issues a brand-new, active refresh token for the given user. */
    public static RefreshToken issue(UUID userId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), userId, tokenHash, expiresAt, false);
    }

    public boolean isActive(Instant now) {
        return !revoked && expiresAt.isAfter(now);
    }

    public void revoke() {
        this.revoked = true;
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

    public boolean isRevoked() {
        return revoked;
    }
}
