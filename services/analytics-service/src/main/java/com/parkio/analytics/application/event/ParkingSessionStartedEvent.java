package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service {@code ParkingSessionStarted} payload
 * (event-contracts.md / S1-P0-08). Privacy-minimized: no coordinates.
 */
public record ParkingSessionStartedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        String status,
        String source,
        Instant startedAt,
        Instant occurredAt) {
}