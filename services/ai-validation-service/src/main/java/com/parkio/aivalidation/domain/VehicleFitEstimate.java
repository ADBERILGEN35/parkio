package com.parkio.aivalidation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Advisory estimate of how well a vehicle type fits the detected space. {@code fitScore} is 0-100. */
public final class VehicleFitEstimate {

    private final UUID id;
    private final VehicleType vehicleType;
    private final int fitScore;
    private final Instant createdAt;

    public VehicleFitEstimate(UUID id, VehicleType vehicleType, int fitScore, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType");
        this.fitScore = Score.require("fitScore", fitScore);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static VehicleFitEstimate of(VehicleType vehicleType, int fitScore, Instant now) {
        return new VehicleFitEstimate(UUID.randomUUID(), vehicleType, fitScore, now);
    }

    public UUID id() {
        return id;
    }

    public VehicleType vehicleType() {
        return vehicleType;
    }

    public int fitScore() {
        return fitScore;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
