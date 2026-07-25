package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service {@code ParkingSessionReminderRequested} payload.
 * Privacy-minimized: no coordinates.
 */
public record ParkingSessionReminderRequestedEvent(
        UUID eventId,
        UUID sessionId,
        UUID userId,
        String stage,
        Instant startedAt,
        Instant lastConfirmedAt,
        Instant occurredAt) {
}
