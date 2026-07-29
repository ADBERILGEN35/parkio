package com.parkio.parking.decision.shadow;

import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.Objects;

/**
 * Legacy publication gate outcome used only for shadow comparison.
 * Captures the status already determined by applyAiValidationResult (or a pure projection).
 */
public final class LegacyPublicationOutcome {

    public enum Kind {
        STALE,
        UNKNOWN_STATUS,
        NO_CHANGE,
        APPLIED
    }

    private final ParkingSpotStatus previousStatus;
    private final ParkingSpotStatus resultingStatus;
    private final Kind kind;

    public LegacyPublicationOutcome(
            ParkingSpotStatus previousStatus, ParkingSpotStatus resultingStatus, Kind kind) {
        this.previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
        this.resultingStatus = Objects.requireNonNull(resultingStatus, "resultingStatus");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public ParkingSpotStatus previousStatus() {
        return previousStatus;
    }

    public ParkingSpotStatus resultingStatus() {
        return resultingStatus;
    }

    public Kind kind() {
        return kind;
    }
}