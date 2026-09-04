package com.parkio.parking.application.event;

import java.time.Instant;
import java.util.UUID;

/** Ids-only erasure command from auth-service (parkio.privacy.erasure). */
public record UserErasureRequestedEvent(
        UUID eventId,
        UUID erasureRequestId,
        UUID authUserId,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureRequested";
}
