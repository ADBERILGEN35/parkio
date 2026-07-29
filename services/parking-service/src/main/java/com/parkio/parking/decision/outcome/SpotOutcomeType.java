package com.parkio.parking.decision.outcome;

/**
 * Canonical post-publish outcome vocabulary for a ParkingSpot opportunity.
 *
 * <p>Vocabulary only in WP-05.2 — no collection, persistence, or trust/reward updates.
 */
public enum SpotOutcomeType {
    PARKED_SUCCESSFULLY,
    ARRIVED_BUT_OCCUPIED,
    LOCATION_NOT_FOUND,
    INVALID_OR_ILLEGAL,
    REPORT_ALREADY_STALE
}