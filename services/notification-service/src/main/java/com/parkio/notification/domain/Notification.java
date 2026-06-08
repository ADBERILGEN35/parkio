package com.parkio.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a single notification to a user. Content (title/body) is
 * denormalised at creation. {@code userId} is the platform-wide authUserId. Pure
 * domain: no framework dependencies.
 *
 * <p>Initial status is derived from the channel: {@code IN_APP} is immediately
 * {@link NotificationStatus#SENT}; external channels start {@link NotificationStatus#PENDING}
 * until a delivery relay sends them (not implemented yet).
 */
public final class Notification {

    private final UUID id;
    private final UUID userId;
    private final NotificationType type;
    private final NotificationChannel channel;
    private final String title;
    private final String body;
    private NotificationStatus status;
    private final Instant createdAt;
    private Instant readAt;
    private final Long version;

    public Notification(UUID id, UUID userId, NotificationType type, NotificationChannel channel,
                        String title, String body, NotificationStatus status, Instant createdAt,
                        Instant readAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.title = Objects.requireNonNull(title, "title");
        this.body = Objects.requireNonNull(body, "body");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.readAt = readAt;
        this.version = version;
    }

    /** Creates a notification with the channel-appropriate initial delivery status. */
    public static Notification create(UUID userId, NotificationType type, NotificationChannel channel,
                                      String title, String body, Instant now) {
        NotificationStatus initial = channel == NotificationChannel.IN_APP
                ? NotificationStatus.SENT
                : NotificationStatus.PENDING;
        return new Notification(UUID.randomUUID(), userId, type, channel, title, body, initial, now, null, null);
    }

    /** Marks the notification read (idempotent). */
    public void markRead(Instant now) {
        if (status == NotificationStatus.READ) {
            return;
        }
        this.status = NotificationStatus.READ;
        this.readAt = now;
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

    public NotificationType type() {
        return type;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public NotificationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant readAt() {
        return readAt;
    }

    public Long version() {
        return version;
    }
}
