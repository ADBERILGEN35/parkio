package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of notification-service's {@code NotificationCreated} payload (event-contracts.md). */
public record NotificationCreatedEvent(
        UUID eventId,
        UUID notificationId,
        UUID userId,
        String notificationType,
        String channel,
        Instant occurredAt) {
}
