package com.parkio.analytics.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service {@code ParkingHistoryDeleted} payload
 * (event-contracts.md / WP-07 PR-5). Privacy-minimized: no coordinates.
 */
public record ParkingHistoryDeletedEvent(
        UUID eventId,
        UUID userId,
        ParkingHistoryDeletionScope scope,
        UUID sessionId,
        Integer deletedCount,
        Instant occurredAt) {
}