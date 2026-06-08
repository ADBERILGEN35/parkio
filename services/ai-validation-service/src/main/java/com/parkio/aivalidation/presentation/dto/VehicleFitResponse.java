package com.parkio.aivalidation.presentation.dto;

import com.parkio.aivalidation.domain.VehicleFitEstimate;
import com.parkio.aivalidation.domain.VehicleType;
import java.time.Instant;
import java.util.UUID;

/** Response view of a vehicle fit estimate. */
public record VehicleFitResponse(
        UUID id,
        VehicleType vehicleType,
        int fitScore,
        Instant createdAt) {

    public static VehicleFitResponse from(VehicleFitEstimate v) {
        return new VehicleFitResponse(v.id(), v.vehicleType(), v.fitScore(), v.createdAt());
    }
}
