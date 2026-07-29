package com.parkio.parking.application.result;

import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.Objects;

/**
 * Already-computed outcome of {@code applyAiValidationResult}. Exposes the
 * in-memory status transition for non-authoritative shadow comparison without
 * an extra database read. Does not change publication behavior.
 */
public final class AiValidationApplyOutcome {

    public enum Kind {
        STALE,
        UNKNOWN_STATUS,
        NO_CHANGE,
        APPLIED
    }

    private final ParkingSpotStatus previousStatus;
    private final ParkingSpotStatus resultingStatus;
    private final Kind kind;

    public AiValidationApplyOutcome(
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