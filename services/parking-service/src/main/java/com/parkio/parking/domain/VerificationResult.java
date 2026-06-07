package com.parkio.parking.domain;

/** Outcome a verifying user reports about a spot. */
public enum VerificationResult {
    AVAILABLE,
    FILLED,
    INVALID,
    ILLEGAL_OR_RISKY,
    WRONG_VEHICLE_SIZE
}
