package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a spot's validity window elapses and it is marked expired. */
public record ParkingSpotExpiredEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotExpired";

    public static ParkingSpotExpiredEvent of(ParkingSpot spot, Instant occurredAt) {
        return new ParkingSpotExpiredEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                spot.status(), occurredAt);
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
