package com.parkio.aivalidation.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotCreated} payload
 * (event-contracts.md). Only the fields this service needs are mirrored; unknown
 * fields are ignored (contracts are duplicated, never shared — ai-context/01).
 */
public record ParkingSpotCreatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        double latitude,
        double longitude,
        String status,
        Instant occurredAt) {
}
