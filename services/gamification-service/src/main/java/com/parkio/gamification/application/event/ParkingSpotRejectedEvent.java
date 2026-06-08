package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotRejected} payload (illegal/risky
 * spot). {@code actorUserId} is the reporting user; {@code result} mirrors the
 * serialized verification result.
 */
public record ParkingSpotRejectedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        String result,
        Instant occurredAt) {
}
