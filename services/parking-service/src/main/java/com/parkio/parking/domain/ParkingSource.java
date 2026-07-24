package com.parkio.parking.domain;

/** How the parking location was selected when the session started. */
public enum ParkingSource {
    MANUAL,
    FACILITY,
    CURB,
    COMMUNITY,
    AUTO
}
