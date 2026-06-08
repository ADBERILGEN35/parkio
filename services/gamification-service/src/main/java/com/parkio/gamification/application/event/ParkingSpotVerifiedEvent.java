package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotVerified} payload. {@code result}
 * mirrors the serialized verification result (e.g. {@code "AVAILABLE"}); {@code actorUserId}
 * is the verifier.
 */
public record ParkingSpotVerifiedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        String result,
        Instant occurredAt) {

    public static final String RESULT_AVAILABLE = "AVAILABLE";

    public boolean isAvailable() {
        return RESULT_AVAILABLE.equals(result);
    }
}
