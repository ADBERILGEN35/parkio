package com.parkio.parking.outcome.evidence;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot of aggregate fields and post-publication timeline. */
public record OutcomeEvidence(
        UUID parkingSpotId,
        ParkingSpotStatus status,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        Instant updatedAt,
        int verificationCount,
        int filledReportCount,
        double confidenceScore,
        OutcomeTimeline timeline) {

    public OutcomeEvidence {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(timeline, "timeline");
        if (verificationCount < 0 || filledReportCount < 0) {
            throw new IllegalArgumentException("verification and filled counts must be non-negative");
        }
        if (confidenceScore < 0.0 || confidenceScore > 1.0) {
            throw new IllegalArgumentException("confidenceScore must be between 0.0 and 1.0");
        }
    }

    public boolean isPublished() {
        return activatedAt != null;
    }

    public boolean isValidationWindowOpen(Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (expiresAt == null) {
            return !status.isTerminal() && !status.isPendingModeration();
        }
        return evaluatedAt.isBefore(expiresAt);
    }
}