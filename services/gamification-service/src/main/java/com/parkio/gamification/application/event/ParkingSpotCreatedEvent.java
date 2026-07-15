package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotCreated} payload (ai-context/01:
 * contracts are duplicated, never shared). Only the fields gamification needs.
 *
 * <p>{@code status} may be null for older events; upload points are awarded for
 * {@code ACTIVE} (or missing status for back-compat). Pending AI-gate statuses skip
 * the award until {@code ParkingSpotActivated}.
 */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        String status,
        Instant occurredAt) {
}
