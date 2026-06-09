package com.parkio.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Record of an attempt to deliver a {@link Notification} on an external channel
 * (currently PUSH). One attempt targets one device token. Pure domain: no framework
 * dependencies.
 *
 * <p>Retry model: a failed send keeps the attempt {@link DeliveryStatus#PENDING} and
 * pushes {@code nextAttemptAt} into the future with exponential backoff
 * ({@code base * 2^(attemptCount-1)}), so the worker retries it no earlier than that
 * instant. Once {@code attemptCount} reaches the configured max it becomes
 * {@link DeliveryStatus#FAILED} (terminal).
 *
 * <p>The raw device-token value is never stored here — only the {@code deviceTokenId}
 * reference and a provider message id / sanitised failure reason. This keeps secrets
 * out of delivery history (ai-context/07).
 */
public final class NotificationDeliveryAttempt {

    private final UUID id;
    private final UUID notificationId;
    private final UUID userId;
    private final NotificationChannel channel;
    private final UUID deviceTokenId;
    private DeliveryStatus status;
    private String providerMessageId;
    private String failureReason;
    private int attemptCount;
    private Instant attemptedAt;
    private Instant nextAttemptAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public NotificationDeliveryAttempt(UUID id, UUID notificationId, UUID userId, NotificationChannel channel,
                                       UUID deviceTokenId, DeliveryStatus status, String providerMessageId,
                                       String failureReason, int attemptCount, Instant attemptedAt,
                                       Instant nextAttemptAt, Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.notificationId = Objects.requireNonNull(notificationId, "notificationId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.deviceTokenId = deviceTokenId;
        this.status = Objects.requireNonNull(status, "status");
        this.providerMessageId = providerMessageId;
        this.failureReason = failureReason;
        this.attemptCount = attemptCount;
        this.attemptedAt = attemptedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A new attempt queued for a specific device token, due immediately. */
    public static NotificationDeliveryAttempt pending(UUID notificationId, UUID userId, NotificationChannel channel,
                                                      UUID deviceTokenId, Instant now) {
        return new NotificationDeliveryAttempt(UUID.randomUUID(), notificationId, userId, channel, deviceTokenId,
                DeliveryStatus.PENDING, null, null, 0, null, now, now, now, null);
    }

    /** An attempt recorded as intentionally skipped (e.g. no active device token). */
    public static NotificationDeliveryAttempt skipped(UUID notificationId, UUID userId, NotificationChannel channel,
                                                      String reason, Instant now) {
        return new NotificationDeliveryAttempt(UUID.randomUUID(), notificationId, userId, channel, null,
                DeliveryStatus.SKIPPED, null, reason, 0, now, null, now, now, null);
    }

    /** Marks the attempt delivered with the provider's message id. */
    public void markSent(String providerMessageId, Instant now) {
        this.status = DeliveryStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.failureReason = null;
        this.attemptCount++;
        this.attemptedAt = now;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    /**
     * Records a failed send. Stays {@link DeliveryStatus#PENDING} with
     * {@code nextAttemptAt = now + baseBackoff * 2^(attemptCount-1)} until
     * {@code maxAttempts} is reached, then becomes terminal {@link DeliveryStatus#FAILED}.
     * {@code reason} must be a sanitised code/message — never a secret or token value.
     */
    public void recordFailure(String reason, int maxAttempts, Duration baseBackoff, Instant now) {
        this.attemptCount++;
        this.failureReason = reason;
        this.attemptedAt = now;
        this.updatedAt = now;
        if (this.attemptCount >= maxAttempts) {
            this.status = DeliveryStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.status = DeliveryStatus.PENDING;
            this.nextAttemptAt = now.plus(backoffFor(this.attemptCount, baseBackoff));
        }
    }

    /** Exponential backoff with a capped exponent so large attempt counts cannot overflow. */
    private static Duration backoffFor(int attemptCount, Duration baseBackoff) {
        long multiplier = 1L << Math.min(attemptCount - 1, 20);
        return baseBackoff.multipliedBy(multiplier);
    }

    public UUID id() {
        return id;
    }

    public UUID notificationId() {
        return notificationId;
    }

    public UUID userId() {
        return userId;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public UUID deviceTokenId() {
        return deviceTokenId;
    }

    public DeliveryStatus status() {
        return status;
    }

    public String providerMessageId() {
        return providerMessageId;
    }

    public String failureReason() {
        return failureReason;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant attemptedAt() {
        return attemptedAt;
    }

    /** Earliest instant the worker may (re)try this attempt; {@code null} when terminal. */
    public Instant nextAttemptAt() {
        return nextAttemptAt;
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
