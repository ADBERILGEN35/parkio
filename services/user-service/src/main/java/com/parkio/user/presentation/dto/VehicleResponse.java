package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.domain.VehicleType;

/** The caller's own vehicle profile. Empty fields mean no vehicle is set. */
public record VehicleResponse(VehicleType vehicleType, String plate) {

    public static VehicleResponse from(UserVehicleProfile v) {
        return new VehicleResponse(v.vehicleType(), v.plate());
    }

    public static VehicleResponse empty() {
        return new VehicleResponse(null, null);
    }
}
