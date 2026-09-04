package com.parkio.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

public record UserErasureAcknowledgedEvent(
        UUID eventId,
        UUID erasureRequestId,
        UUID authUserId,
        String serviceName,
        String status,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureAcknowledged";
    public static final String AGGREGATE_TYPE = "AccountErasure";
}
