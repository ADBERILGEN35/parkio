package com.parkio.parking.exposure;

/** Bounded availability adapter for exposure scoring. Not a live engine rerun. */
public enum ExposureAvailabilityState {
    AVAILABLE,
    LIKELY_AVAILABLE,
    UNKNOWN,
    LIKELY_OCCUPIED,
    UNAVAILABLE,
    EXPIRED
}
