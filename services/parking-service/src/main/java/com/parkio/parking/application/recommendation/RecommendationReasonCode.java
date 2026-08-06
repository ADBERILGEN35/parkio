package com.parkio.parking.application.recommendation;

/**
 * Baseline reason codes for WP-SPA-05. These describe candidates; they do
 * <em>not</em> drive ordering. Weighted ranking arrives in WP-SPA-06.
 */
public enum RecommendationReasonCode {
    CLOSE_TO_DESTINATION,
    LIVE_AVAILABILITY,
    HIGH_CAPACITY,
    STATIC_INVENTORY,
    COMMUNITY_FRESH,
    INVENTORY_DEGRADED
}
