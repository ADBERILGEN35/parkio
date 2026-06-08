package com.parkio.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's push device token. Unique per (user, token). Deactivated rather than
 * deleted so delivery history remains intact. Pure domain.
 */
public final class DeviceToken {

    private final UUID id;
    private final UUID userId;
    private final String token;
    private final DevicePlatform platform;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public DeviceToken(UUID id, UUID userId, String token, DevicePlatform platform, boolean active,
                       Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.token = Objects.requireNonNull(token, "token");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static DeviceToken register(UUID userId, String token, DevicePlatform platform, Instant now) {
        return new DeviceToken(UUID.randomUUID(), userId, token, platform, true, now, now, null);
    }

    public void deactivate(Instant now) {
        if (!active) {
            return;
        }
        this.active = false;
        this.updatedAt = now;
    }

    /** Re-activates a previously deactivated token (idempotent re-registration). */
    public void reactivate(Instant now) {
        if (active) {
            return;
        }
        this.active = true;
        this.updatedAt = now;
    }

    public boolean isOwnedBy(UUID candidateUserId) {
        return userId.equals(candidateUserId);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String token() {
        return token;
    }

    public DevicePlatform platform() {
        return platform;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
