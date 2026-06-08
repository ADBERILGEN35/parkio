package com.parkio.notification.domain.event;

import com.parkio.notification.domain.Notification;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a notification is created. Written to the transactional outbox; a
 * relay (not implemented yet) will publish it. Carries only IDs and type/channel —
 * not the rendered content (ai-context/06).
 */
public record NotificationCreatedEvent(
        UUID eventId,
        UUID notificationId,
        UUID userId,
        String notificationType,
        String channel,
        Instant occurredAt) {

    public static final String TYPE = "NotificationCreated";
    public static final String AGGREGATE_TYPE = "Notification";

    public static NotificationCreatedEvent of(Notification notification, Instant occurredAt) {
        return new NotificationCreatedEvent(UUID.randomUUID(), notification.id(), notification.userId(),
                notification.type().name(), notification.channel().name(), occurredAt);
    }
}
