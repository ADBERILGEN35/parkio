package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotCreated} payload (ai-context/01:
 * contracts are duplicated, never shared). Only the fields gamification needs.
 */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        Instant occurredAt) {
}
