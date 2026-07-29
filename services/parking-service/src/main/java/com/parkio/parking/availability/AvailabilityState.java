package com.parkio.parking.availability;

/**
 * Occupancy-oriented availability classification for a parking opportunity.
 *
 * <p>Distinct from {@code PublicationDisposition} and {@code ParkingSpotStatus}.
 * A spot may remain published while availability decays toward {@link #EXPIRED}.
 */
public enum AvailabilityState {

    /** Fresh published report with strong remaining TTL and no occupancy signals. */
    AVAILABLE,

    /** Published and still plausible, but aging or lightly contested. */
    LIKELY_AVAILABLE,

    /** Insufficient or conflicting occupancy signals, or not yet published. */
    UNKNOWN,

    /** Occupancy signals outweigh freshness (suspicious, partial fill reports). */
    LIKELY_OCCUPIED,

    /** Confirmed unavailable for discovery purposes (filled, rejected, terminal). */
    UNAVAILABLE,

    /** Advertised validity window elapsed or aggregate marked expired. */
    EXPIRED
}
