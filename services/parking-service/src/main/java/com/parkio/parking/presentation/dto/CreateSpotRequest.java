package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.ViolationReason;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * Create-spot request. Coordinate ranges and description length are enforced in the
 * domain; here we enforce presence and basic shape. {@code mediaId} is an external
 * reference to media-service (not dereferenced in this foundation).
 */
public record CreateSpotRequest(
        @NotNull UUID mediaId,
        @NotNull Double latitude,
        @NotNull Double longitude,
        @Size(max = 512) String addressText,
        @Size(max = 1000) String description,
        boolean manualLocationEdited,
        @NotEmpty Set<VehicleType> suitableVehicleTypes,
        @NotNull ParkingContext parkingContext,
        @NotNull LegalStatus legalStatus,
        Set<ViolationReason> violationReasons) {
}
