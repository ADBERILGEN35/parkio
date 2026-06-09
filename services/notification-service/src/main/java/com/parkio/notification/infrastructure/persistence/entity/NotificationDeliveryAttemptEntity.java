package com.parkio.notification.infrastructure.persistence.entity;

import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code notification_delivery_attempts}. */
@Entity
@Table(name = "notification_delivery_attempts")
public class NotificationDeliveryAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(name = "device_token_id")
    private UUID deviceTokenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeliveryStatus status;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "attempted_at")
    private Instant attemptedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected NotificationDeliveryAttemptEntity() {
        // for JPA
    }

    public NotificationDeliveryAttemptEntity(UUID id, UUID notificationId, UUID userId, NotificationChannel channel,
                                             UUID deviceTokenId, DeliveryStatus status, String providerMessageId,
                                             String failureReason, int attemptCount, Instant attemptedAt,
                                             Instant nextAttemptAt, Instant createdAt, Instant updatedAt,
                                             Long version) {
        this.id = id;
        this.notificationId = notificationId;
        this.userId = userId;
        this.channel = channel;
        this.deviceTokenId = deviceTokenId;
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.failureReason = failureReason;
        this.attemptCount = attemptCount;
        this.attemptedAt = attemptedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getDeviceTokenId() {
        return deviceTokenId;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
