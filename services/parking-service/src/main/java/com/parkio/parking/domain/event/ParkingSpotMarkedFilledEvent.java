package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a spot becomes filled from filled-reports (not a direct claim). */
public record ParkingSpotMarkedFilledEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotMarkedFilled";

    public static ParkingSpotMarkedFilledEvent of(ParkingSpot spot, Instant occurredAt) {
        return new ParkingSpotMarkedFilledEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
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
