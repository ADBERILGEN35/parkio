package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of gamification-service's {@code PointsDeducted} payload (event-contracts.md). */
public record PointsDeductedEvent(
        UUID eventId,
        UUID userId,
        long points,
        String sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt) {
}
