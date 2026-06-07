package com.parkio.parking.application.command;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.ViolationReason;
import java.util.Set;
import java.util.UUID;

/** Request to create a parking spot. {@code mediaId} is an external reference. */
public record CreateSpotCommand(
        UUID ownerUserId,
        UUID mediaId,
        double latitude,
        double longitude,
        String addressText,
        String description,
        boolean manualLocationEdited,
        Set<VehicleType> suitableVehicleTypes,
        ParkingContext parkingContext,
        LegalStatus legalStatus,
        Set<ViolationReason> violationReasons) {
}
