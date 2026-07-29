package com.parkio.parking.decision.normalization;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Category-B parking-spot context supplied alongside an AI result for normalization.
 * Does not load data; callers pass values already available in-process.
 */
public record ParkingSpotEvidenceContext(
        UUID parkingSpotId,
        UUID mediaId,
        double latitude,
        double longitude,
        String legalStatusName,
        boolean manualLocationEdited,
        Instant moderationDecidedAt) {

    public ParkingSpotEvidenceContext {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(mediaId, "mediaId");
        legalStatusName = legalStatusName == null ? "" : legalStatusName.trim().toUpperCase();
    }

    public boolean isStaleModerationEvent(Instant occurredAt) {
        return occurredAt != null
                && moderationDecidedAt != null
                && occurredAt.isBefore(moderationDecidedAt);
    }
}
