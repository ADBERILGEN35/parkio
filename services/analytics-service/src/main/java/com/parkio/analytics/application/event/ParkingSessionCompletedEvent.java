package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service {@code ParkingSessionCompleted} payload
 * (event-contracts.md / S1-P0-08). Privacy-minimized: no coordinates.
 *
 * <p>Additive fields ({@code completionReason}, {@code confirmedAt},
 * {@code sessionDurationSeconds}) are optional for older producers.
 */
public record ParkingSessionCompletedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        String status,
        String source,
        Instant startedAt,
        Instant endedAt,
        Instant occurredAt,
        String completionReason,
        Instant confirmedAt,
        Long sessionDurationSeconds) {

    /** Backward-compatible constructor for tests and older call sites. */
    public ParkingSessionCompletedEvent(
            UUID eventId,
            UUID sessionId,
            UUID userId,
            String status,
            String source,
            Instant startedAt,
            Instant endedAt,
            Instant occurredAt) {
        this(eventId, sessionId, userId, status, source, startedAt, endedAt, occurredAt, null, null, null);
    }
}
