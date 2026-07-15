package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of parking-service's {@code ParkingSpotActivated} payload. Emitted when
 * AI validation promotes a spot to ACTIVE; used to award the upload-owner reward that
 * was deferred at create time under the AI publication gate.
 */
public record ParkingSpotActivatedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        Instant occurredAt) {
}