package com.parkio.parking.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable continuation key for parking history ordered by start time and identifier. */
public record ParkingSessionHistoryCursor(Instant startedAt, UUID id) {

    public ParkingSessionHistoryCursor {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(id, "id");
    }
}
