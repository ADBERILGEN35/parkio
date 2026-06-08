package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of parking-service's {@code ParkingSpotRejected} payload (event-contracts.md). */
public record ParkingSpotRejectedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        String result,
        Instant occurredAt) {
}
