package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code ParkingSpotRejectedByModerator} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 *
 * <p>{@code ownerUserId} is the spot owner when moderation knows it (cases opened from a
 * community {@code ParkingSpotRejected}); it is null for cases opened from a report or an
 * AI/media signal. The handler applies the owner penalty only when it is present.
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
