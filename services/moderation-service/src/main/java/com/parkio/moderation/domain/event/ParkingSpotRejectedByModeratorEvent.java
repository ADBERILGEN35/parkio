package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a moderator rejects/marks-risky a parking spot. parking-service
 * consumes this to set the spot's status (moderation never writes parking data).
 */
public record ParkingSpotRejectedByModeratorEvent(
        UUID eventId,
        UUID caseId,
        UUID parkingSpotId,
        UUID moderatorId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ParkingSpotRejectedByModerator";
    public static final String AGGREGATE_TYPE = "ParkingSpot";

    public static ParkingSpotRejectedByModeratorEvent of(UUID caseId, UUID parkingSpotId,
                                                         UUID moderatorId, Instant occurredAt) {
        return new ParkingSpotRejectedByModeratorEvent(UUID.randomUUID(), caseId, parkingSpotId, moderatorId, occurredAt);
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
