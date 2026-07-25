package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a spot's AI publication gate did not answer within its deadline and a
 * bounded retry is due. Published through the outbox onto the ordinary parking-spot topic
 * (no direct call into ai-validation-service), so the retry path keeps the same
 * at-least-once, dead-letterable delivery guarantees as the original request.
 *
 * <p>{@code attempt} is the 1-based retry number and is bounded by
 * {@code parkio.parking.moderation.max-validation-attempts}; exhausting it moves the spot
 * to the terminal {@code REVIEW_FAILED} state rather than leaving it pending forever.
 */
public record ParkingSpotModerationRetryRequestedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        int attempt,
        Instant deadlineAt,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotModerationRetryRequested";

    public static ParkingSpotModerationRetryRequestedEvent of(ParkingSpot spot, Instant occurredAt) {
        return new ParkingSpotModerationRetryRequestedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                spot.mediaId(), spot.moderationAttempts(), spot.moderationDeadlineAt(), occurredAt);
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
