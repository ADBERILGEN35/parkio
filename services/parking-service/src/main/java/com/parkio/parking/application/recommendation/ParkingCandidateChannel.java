package com.parkio.parking.application.recommendation;

/**
 * Inventory channel for a recommendation candidate.
 *
 * <p>v1 supports community spots and municipal facilities only. Provider
 * facilities are deferred (WP-SPA-13).
 */
public enum ParkingCandidateChannel {
    COMMUNITY_SPOT,
    MUNICIPAL_FACILITY
}
