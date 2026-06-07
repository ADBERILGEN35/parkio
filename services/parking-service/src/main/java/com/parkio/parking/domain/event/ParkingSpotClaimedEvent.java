package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user successfully claims/parks in a spot, marking it filled.
 * {@code actorUserId} is the claimer (the user who took the spot). This is the
 * signal gamification uses to reward both the claimer and the {@code ownerUserId}.
 */
public record ParkingSpotClaimedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotClaimed";

    public static ParkingSpotClaimedEvent of(ParkingSpot spot, UUID actorUserId, Instant occurredAt) {
        return new ParkingSpotClaimedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                actorUserId, spot.status(), occurredAt);
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
