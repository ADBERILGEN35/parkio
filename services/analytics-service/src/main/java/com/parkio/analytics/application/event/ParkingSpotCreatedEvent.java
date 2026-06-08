package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of parking-service's {@code ParkingSpotCreated} payload (event-contracts.md). */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        Instant occurredAt) {
}
