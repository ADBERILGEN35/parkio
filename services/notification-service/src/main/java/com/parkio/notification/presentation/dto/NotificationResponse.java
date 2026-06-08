package com.parkio.notification.presentation.dto;

import com.parkio.notification.domain.Notification;
import java.time.Instant;
import java.util.UUID;

/** A notification as shown to its recipient. */
public record NotificationResponse(
        UUID id,
        String type,
        String channel,
        String title,
        String body,
        String status,
        Instant createdAt,
        Instant readAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.id(), n.type().name(), n.channel().name(), n.title(), n.body(),
                n.status().name(), n.createdAt(), n.readAt());
    }
}
