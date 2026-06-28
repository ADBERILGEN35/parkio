package com.parkio.notification.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.infrastructure.persistence.entity.DeviceTokenEntity;
import com.parkio.notification.infrastructure.persistence.entity.NotificationDeliveryAttemptEntity;
import com.parkio.notification.infrastructure.persistence.entity.NotificationEntity;
import com.parkio.notification.infrastructure.persistence.entity.NotificationPreferenceEntity;
import com.parkio.notification.infrastructure.persistence.entity.NotificationTemplateEntity;
import java.util.Map;

/**
 * Translates between domain models and JPA entities, keeping adapters thin and the
 * domain persistence-agnostic.
 */
public final class NotificationPersistenceMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private NotificationPersistenceMapper() {
    }

    public static Notification toDomain(NotificationEntity e) {
        return new Notification(e.getId(), e.getUserId(), e.getType(), e.getChannel(), e.getTitle(),
                e.getBody(), readMetadata(e.getMetadata()), e.getStatus(), e.getCreatedAt(), e.getReadAt(),
                e.getVersion());
    }

    public static NotificationEntity toEntity(Notification n) {
        return new NotificationEntity(n.id(), n.userId(), n.type(), n.channel(), n.title(), n.body(),
                writeMetadata(n.metadata()), n.status(), n.createdAt(), n.readAt(), n.version());
    }

    public static DeviceToken toDomain(DeviceTokenEntity e) {
        return new DeviceToken(e.getId(), e.getUserId(), e.getToken(), e.getPlatform(), e.isActive(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }

    public static DeviceTokenEntity toEntity(DeviceToken t) {
        return new DeviceTokenEntity(t.id(), t.userId(), t.token(), t.platform(), t.active(),
                t.createdAt(), t.updatedAt(), t.version());
    }

    public static NotificationPreference toDomain(NotificationPreferenceEntity e) {
        return new NotificationPreference(e.getUserId(), e.isPushEnabled(), e.isEmailEnabled(),
                e.isInAppEnabled(), e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }

    public static NotificationPreferenceEntity toEntity(NotificationPreference p) {
        return new NotificationPreferenceEntity(p.userId(), p.pushEnabled(), p.emailEnabled(),
                p.inAppEnabled(), p.createdAt(), p.updatedAt(), p.version());
    }

    public static NotificationTemplate toDomain(NotificationTemplateEntity e) {
        return new NotificationTemplate(e.getType(), e.getTitleTemplate(), e.getBodyTemplate());
    }

    public static NotificationDeliveryAttempt toDomain(NotificationDeliveryAttemptEntity e) {
        return new NotificationDeliveryAttempt(e.getId(), e.getNotificationId(), e.getUserId(), e.getChannel(),
                e.getDeviceTokenId(), e.getStatus(), e.getProviderMessageId(), e.getFailureReason(),
                e.getAttemptCount(), e.getAttemptedAt(), e.getNextAttemptAt(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getVersion());
    }

    public static NotificationDeliveryAttemptEntity toEntity(NotificationDeliveryAttempt a) {
        return new NotificationDeliveryAttemptEntity(a.id(), a.notificationId(), a.userId(), a.channel(),
                a.deviceTokenId(), a.status(), a.providerMessageId(), a.failureReason(), a.attemptCount(),
                a.attemptedAt(), a.nextAttemptAt(), a.createdAt(), a.updatedAt(), a.version());
    }

    private static Map<String, String> readMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize notification metadata", e);
        }
    }

    private static String writeMetadata(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification metadata", e);
        }
    }
}
