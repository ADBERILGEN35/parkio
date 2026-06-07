package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VerificationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a spot is rejected as illegal/risky via verification.
 * {@code actorUserId} is the reporting user.
 */
public record ParkingSpotRejectedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        VerificationResult result,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotRejected";

    public static ParkingSpotRejectedEvent of(ParkingSpot spot, UUID actorUserId,
                                              VerificationResult result, Instant occurredAt) {
        return new ParkingSpotRejectedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                actorUserId, result, spot.status(), occurredAt);
    }

    @Override
    public UUID aggregateId() {
        return parkingSpotId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
