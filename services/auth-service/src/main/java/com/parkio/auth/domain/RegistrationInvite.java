package com.parkio.auth.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Registration invite record. Only the token hash is stored. */
public final class RegistrationInvite {

    private final UUID id;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant consumedAt;
    private final Instant createdAt;
    private final String createdBy;
    private final Long version;

    public RegistrationInvite(UUID id,
                              String tokenHash,
                              Instant expiresAt,
                              Instant consumedAt,
                              Instant createdAt,
                              String createdBy,
                              Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.version = version;
    }

    public static RegistrationInvite issue(String tokenHash,
                                           Instant expiresAt,
                                           Instant now,
                                           String createdBy) {
        return new RegistrationInvite(
                UUID.randomUUID(), tokenHash, expiresAt, null, now, createdBy, null);
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

    public String createdBy() {
        return createdBy;
    }

    public Long version() {
        return version;
    }
}
