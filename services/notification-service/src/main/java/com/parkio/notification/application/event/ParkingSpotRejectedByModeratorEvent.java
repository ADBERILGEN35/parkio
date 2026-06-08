package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code ParkingSpotRejectedByModerator} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 * {@code ownerUserId} is the spot owner when moderation knows it; otherwise null, in
 * which case the owner notification is skipped.
 */
public record ParkingSpotRejectedByModeratorEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID moderatorUserId,
        UUID moderationCaseId,
        String reason,
        Instant occurredAt) {

    public static final String TYPE = "ParkingSpotRejectedByModerator";
}
