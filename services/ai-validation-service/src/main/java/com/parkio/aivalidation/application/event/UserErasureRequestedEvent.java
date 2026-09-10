package com.parkio.aivalidation.application.event;

import java.time.Instant;
import java.util.UUID;

public record UserErasureRequestedEvent(
        UUID eventId,
        UUID erasureRequestId,
        UUID authUserId,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureRequested";
}
