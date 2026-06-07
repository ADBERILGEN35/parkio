package com.parkio.user.application.command;

import com.parkio.user.domain.VehicleType;

/** Full replacement of the vehicle profile (PUT semantics); both fields optional. */
public record UpsertVehicleCommand(VehicleType vehicleType, String plate) {
}
