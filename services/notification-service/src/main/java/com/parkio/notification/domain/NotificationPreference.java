package com.parkio.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user delivery-channel preferences. {@code userId} is the authUserId. Defaults
 * enable every channel. Pure domain.
 */
public final class NotificationPreference {

    private final UUID userId;
    private boolean pushEnabled;
    private boolean emailEnabled;
    private boolean inAppEnabled;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public NotificationPreference(UUID userId, boolean pushEnabled, boolean emailEnabled,
                                  boolean inAppEnabled, Instant createdAt, Instant updatedAt, Long version) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.pushEnabled = pushEnabled;
        this.emailEnabled = emailEnabled;
        this.inAppEnabled = inAppEnabled;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static NotificationPreference createDefault(UUID userId, Instant now) {
        return new NotificationPreference(userId, true, true, true, now, now, null);
    }

    /** Applies a partial update; {@code null} fields are left unchanged. */
    public void update(Boolean pushEnabled, Boolean emailEnabled, Boolean inAppEnabled, Instant now) {
        if (pushEnabled != null) {
            this.pushEnabled = pushEnabled;
        }
        if (emailEnabled != null) {
            this.emailEnabled = emailEnabled;
        }
        if (inAppEnabled != null) {
            this.inAppEnabled = inAppEnabled;
        }
        this.updatedAt = now;
    }

    public UUID userId() {
        return userId;
    }

    public boolean pushEnabled() {
        return pushEnabled;
    }

    public boolean emailEnabled() {
        return emailEnabled;
    }

    public boolean inAppEnabled() {
        return inAppEnabled;
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
