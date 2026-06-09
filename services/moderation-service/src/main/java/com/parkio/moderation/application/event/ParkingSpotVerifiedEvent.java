package com.parkio.moderation.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of parking-service's {@code ParkingSpotVerified} payload. */
public record ParkingSpotVerifiedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID actorUserId,
        String result,
        Instant occurredAt) {

    public boolean isIllegalOrRisky() {
        return "ILLEGAL_OR_RISKY".equals(result);
    }
}
