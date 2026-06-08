package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of gamification-service's {@code UserLevelChanged} payload (event-contracts.md). */
public record UserLevelChangedEvent(
        UUID eventId,
        UUID userId,
        int previousLevel,
        int newLevel,
        long totalPoints,
        Instant occurredAt) {
}
