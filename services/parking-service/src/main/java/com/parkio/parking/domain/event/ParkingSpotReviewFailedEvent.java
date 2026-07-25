package com.parkio.parking.domain.event;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when the moderation pipeline never reached a verdict for a spot and it was moved
 * to the terminal {@link ParkingSpotStatus#REVIEW_FAILED} state — either the human review
 * window elapsed, or the bounded AI validation retries were exhausted.
 *
 * <p>Downstream this is the signal to tell the owner their submission could not be
 * reviewed and invite them to resubmit; it is never a penalty, since the failure is the
 * platform's, not the contributor's.
 */
public record ParkingSpotReviewFailedEvent(
        UUID eventId,
        UUID parkingSpotId,
        UUID ownerUserId,
        UUID mediaId,
        ParkingSpotStatus previousStatus,
        String reason,
        int attempts,
        Instant occurredAt) implements ParkingEvent {

    public static final String TYPE = "ParkingSpotReviewFailed";

    /** Bounded AI publication-gate retries were exhausted without a verdict. */
    public static final String REASON_RETRIES_EXHAUSTED = "MODERATION_RETRIES_EXHAUSTED";
    /** The human review window elapsed without a moderator decision. */
    public static final String REASON_REVIEW_TIMEOUT = "MODERATION_REVIEW_TIMEOUT";
    /**
     * An approval arrived after {@code maxPublishableAge} from creation — publishing
     * would advertise a fresh TTL for an availability report that is already untrustworthy.
     */
    public static final String REASON_STALE_BEFORE_PUBLICATION = "STALE_BEFORE_PUBLICATION";

    public static ParkingSpotReviewFailedEvent of(ParkingSpot spot, ParkingSpotStatus previousStatus,
                                                  String reason, Instant occurredAt) {
        return new ParkingSpotReviewFailedEvent(UUID.randomUUID(), spot.id(), spot.ownerUserId(),
                spot.mediaId(), previousStatus, reason, spot.moderationAttempts(), occurredAt);
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
