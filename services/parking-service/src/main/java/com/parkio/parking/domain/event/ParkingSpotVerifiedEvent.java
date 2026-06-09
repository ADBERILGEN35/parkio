package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VerificationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted for an available confirmation or an unconfirmed illegal/risky community
 * signal. {@code actorUserId} is the verifier/reporter.
 */
public record ParkingSpotVerifiedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        VerificationResult result,
        int verificationCount,
        ParkingSpotStatus status,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotVerified";

    public static ParkingSpotVerifiedEvent of(ParkingSpot spot, UUID actorUserId,
                                              VerificationResult result, Instant occurredAt) {
        return new ParkingSpotVerifiedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                actorUserId, result, spot.verificationCount(), spot.status(), occurredAt);
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
