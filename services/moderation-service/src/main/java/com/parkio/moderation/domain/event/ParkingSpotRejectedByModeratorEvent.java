package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a moderator rejects/marks-risky a parking spot. Owner-targeted: consumed
 * by parking-service (authoritative status transition), gamification-service (owner
 * penalty), and notification-service (warn the owner). Parking applies it without
 * re-emitting a community rejection event, preserving the loop guard.
 *
 * <p>{@code ownerUserId} is the spot owner when the case knows it (cases opened from a
 * community parking signal carry it); it is null for cases opened from a user
 * report or an AI/media signal, in which case downstream owner penalties/notifications are
 * skipped.
 */
public record ParkingSpotRejectedByModeratorEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID moderatorUserId,
        UUID moderationCaseId,
        String reason,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ParkingSpotRejectedByModerator";
    public static final String AGGREGATE_TYPE = "ParkingSpot";

    public static ParkingSpotRejectedByModeratorEvent of(UUID moderationCaseId, UUID parkingSpotId,
                                                         UUID ownerUserId, UUID moderatorUserId,
                                                         String reason, Instant occurredAt) {
        return new ParkingSpotRejectedByModeratorEvent(UUID.randomUUID(), parkingSpotId, ownerUserId,
                moderatorUserId, moderationCaseId, reason, occurredAt);
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public UUID aggregateId() {
        return parkingSpotId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
