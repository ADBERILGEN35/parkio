package com.parkio.notification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code notification_preferences}. */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferenceEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected NotificationPreferenceEntity() {
        // for JPA
    }

    public NotificationPreferenceEntity(UUID userId, boolean pushEnabled, boolean emailEnabled,
                                        boolean inAppEnabled, Instant createdAt, Instant updatedAt, Long version) {
        this.userId = userId;
        this.pushEnabled = pushEnabled;
        this.emailEnabled = emailEnabled;
        this.inAppEnabled = inAppEnabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
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
