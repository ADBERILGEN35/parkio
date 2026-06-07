package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a parking spot is created. Carries only IDs and coordinates. */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        double latitude,
        double longitude,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotCreated";

    public static ParkingSpotCreatedEvent of(ParkingSpot spot, Instant occurredAt) {
        return new ParkingSpotCreatedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                spot.mediaId(), spot.latitude(), spot.longitude(), spot.status(), occurredAt);
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
