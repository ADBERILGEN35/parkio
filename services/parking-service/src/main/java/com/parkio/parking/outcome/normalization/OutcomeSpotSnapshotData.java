package com.parkio.parking.outcome.normalization;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutcomeSpotSnapshotData(UUID parkingSpotId, Instant createdAt) {

    public OutcomeSpotSnapshotData {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}