package com.parkio.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Password reset token record. Only the token hash is stored. */
public final class PasswordResetToken {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant consumedAt;
    private final Instant createdAt;
    private final Long version;

    public PasswordResetToken(UUID id,
                              UUID userId,
                              String tokenHash,
                              Instant expiresAt,
                              Instant consumedAt,
                              Instant createdAt,
                              Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    public static PasswordResetToken issue(UUID userId, String tokenHash, Instant expiresAt, Instant now) {
        return new PasswordResetToken(UUID.randomUUID(), userId, tokenHash, expiresAt, null, now, null);
    }

    public boolean isActive(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        if (consumedAt == null) {
            consumedAt = Objects.requireNonNull(now, "now");
        }
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

    public Instant consumedAt() {
        return consumedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
