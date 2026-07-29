package com.parkio.parking.availability;

/**
 * Documented availability state machine transitions for decay and occupancy signals.
 *
 * <p>Publication transitions never appear here; they belong to the Decision Engine
 * and {@code ParkingSpot} lifecycle.
 */
public enum AvailabilityTransition {

    FRESH_TO_LIKELY_AVAILABLE,
    LIKELY_AVAILABLE_TO_UNKNOWN,
    UNKNOWN_TO_LIKELY_OCCUPIED,
    LIKELY_OCCUPIED_TO_UNAVAILABLE,
    ANY_PUBLISHED_TO_EXPIRED,
    PENDING_TO_UNKNOWN,
    SIGNAL_TO_UNAVAILABLE
}
