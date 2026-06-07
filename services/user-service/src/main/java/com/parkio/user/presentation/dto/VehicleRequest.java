package com.parkio.user.presentation.dto;

import com.parkio.user.domain.VehicleType;
import jakarta.validation.constraints.Size;

/**
 * Full vehicle replacement (PUT). Both fields are optional; the plate is private
 * and never returned in a public profile.
 */
public record VehicleRequest(
        VehicleType vehicleType,
        @Size(max = 16) String plate) {
}
