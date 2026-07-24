package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service {@code ParkingSessionCancelled} payload
 * (event-contracts.md / S1-P0-08). Privacy-minimized: no coordinates.
 */
public record ParkingSessionCancelledEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        String status,
        String source,
        Instant startedAt,
        Instant endedAt,
        Instant occurredAt) {
}