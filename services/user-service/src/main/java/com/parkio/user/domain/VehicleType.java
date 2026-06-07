package com.parkio.user.domain;

/**
 * Vehicle sizes a user may drive (ai-context/02). Used to match a user's vehicle
 * to a parking spot's declared fit. Optional on a profile.
 */
public enum VehicleType {
    MOTORCYCLE,
    SMALL_CAR,
    SEDAN,
    SUV,
    VAN,
    TRUCK
}
