package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a parking spot becomes publicly discoverable after AI validation
 * passes ({@link ParkingSpotStatus#ACTIVE}).
 */
public record ParkingSpotActivatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotActivated";

    public static ParkingSpotActivatedEvent of(ParkingSpot spot, Instant occurredAt) {
        return new ParkingSpotActivatedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                spot.mediaId(), spot.status(), occurredAt);
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