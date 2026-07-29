package com.parkio.parking.availability;

/**
 * Temporal freshness band derived from report age and remaining TTL.
 *
 * <p>Availability changes over time; publication decisions do not.
 */
public enum AvailabilityFreshness {

    FRESH,
    AGING,
    STALE,
    EXPIRED
}
