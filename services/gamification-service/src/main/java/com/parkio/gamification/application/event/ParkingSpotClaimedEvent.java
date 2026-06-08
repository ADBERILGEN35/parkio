package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotClaimed} payload. {@code actorUserId}
 * is the user who successfully claimed/parked in the spot.
 */
public record ParkingSpotClaimedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        Instant occurredAt) {
}
