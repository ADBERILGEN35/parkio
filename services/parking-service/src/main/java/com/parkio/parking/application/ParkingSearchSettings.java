package com.parkio.parking.application;

/**
 * Tunable nearby-search bounds, supplied by infrastructure from
 * {@code parkio.parking.search.*}. Kept as a plain value in the application layer
 * so the service has no dependency on Spring configuration types. {@code max*}
 * cap client-supplied inputs (out-of-range requests are rejected, not clamped).
 */
public record ParkingSearchSettings(
        double defaultRadiusMeters,
        int defaultResultLimit,
        double maxRadiusMeters,
        int maxResultLimit) {
}
