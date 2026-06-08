package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotCreated} payload (event-contracts.md;
 * contracts are duplicated, never shared). Only the fields notification-service needs.
 */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        Instant occurredAt) {
}
