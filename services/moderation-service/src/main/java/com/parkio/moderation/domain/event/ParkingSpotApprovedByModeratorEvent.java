package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a moderator approves a parking spot, clearing it for publication. The
 * counterpart to {@link ParkingSpotRejectedByModeratorEvent}, and the only path by which a
 * spot held in {@code PENDING_REVIEW} can become visible: AI emits a single verdict per
 * spot, so without an explicit approval signal an uncertain spot would wait forever.
 *
 * <p>Consumed by parking-service, which starts the spot's advertised lifetime from the
 * moment of approval — the contributor receives the full visibility window however long
 * the review took. moderation-service still never mutates parking's data directly
 * (ai-context/03); this is purely an event.
 *
 * <p>{@code ownerUserId} is the spot owner when the case knows it, and null for cases
 * opened from a report or an AI/media signal.
 */
public record ParkingSpotApprovedByModeratorEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID moderatorUserId,
        UUID moderationCaseId,
        String reason,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ParkingSpotApprovedByModerator";
    public static final String AGGREGATE_TYPE = "ParkingSpot";

    public static ParkingSpotApprovedByModeratorEvent of(UUID moderationCaseId, UUID parkingSpotId,
                                                         UUID ownerUserId, UUID moderatorUserId,
                                                         String reason, Instant occurredAt) {
        return new ParkingSpotApprovedByModeratorEvent(UUID.randomUUID(), parkingSpotId, ownerUserId,
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
