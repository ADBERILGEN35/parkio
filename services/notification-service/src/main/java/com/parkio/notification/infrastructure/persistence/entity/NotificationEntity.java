package com.parkio.notification.infrastructure.persistence.entity;

import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationStatus;
import com.parkio.notification.domain.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code notifications}. A persistence detail, not the domain. */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "metadata", nullable = false, updatable = false)
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected NotificationEntity() {
        // for JPA
    }

    public NotificationEntity(UUID id, UUID userId, NotificationType type, NotificationChannel channel,
                              String title, String body, NotificationStatus status, Instant createdAt,
                              Instant readAt, Long version) {
        this(id, userId, type, channel, title, body, "{}", status, createdAt, readAt, version);
    }

    public NotificationEntity(UUID id, UUID userId, NotificationType type, NotificationChannel channel,
                              String title, String body, String metadata, NotificationStatus status,
                              Instant createdAt, Instant readAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.channel = channel;
        this.title = title;
        this.body = body;
        this.metadata = metadata == null || metadata.isBlank() ? "{}" : metadata;
        this.status = status;
        this.createdAt = createdAt;
        this.readAt = readAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getMetadata() {
        return metadata;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Long getVersion() {
        return version;
    }
}
