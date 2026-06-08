package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of parking-service's {@code ParkingSpotClaimed} payload (event-contracts.md). */
public record ParkingSpotClaimedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        Instant occurredAt) {
}
