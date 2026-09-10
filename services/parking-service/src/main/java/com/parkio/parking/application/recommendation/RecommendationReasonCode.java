package com.parkio.parking.application.recommendation;

/**
 * Reason codes for recommendation candidates / warnings.
 *
 * <p>SPA-05 baseline codes describe candidates; SPA-06 adds {@link #FAVOURITE}
 * and {@link #HIGH_CONFIDENCE}. Codes do not inject client-supplied scores.
 */
public enum RecommendationReasonCode {
    CLOSE_TO_DESTINATION,
    LIVE_AVAILABILITY,
    HIGH_CAPACITY,
    STATIC_INVENTORY,
    COMMUNITY_FRESH,
    INVENTORY_DEGRADED,
    FAVOURITE,
    HIGH_CONFIDENCE
}
