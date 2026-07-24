package com.parkio.parking.domain;

/** Lifecycle states for a parking session. Terminal states cannot transition again. */
public enum ParkingSessionStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
