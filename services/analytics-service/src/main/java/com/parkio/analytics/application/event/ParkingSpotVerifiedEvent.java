package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of parking-service's {@code ParkingSpotVerified} payload (event-contracts.md). */
public record ParkingSpotVerifiedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        String result,
        Instant occurredAt) {
}
