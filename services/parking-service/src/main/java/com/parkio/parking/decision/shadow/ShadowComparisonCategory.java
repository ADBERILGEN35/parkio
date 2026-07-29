package com.parkio.parking.decision.shadow;

/**
 * Classification of agreement between a legacy ParkingSpotStatus outcome and a
 * shadow PublicationDisposition. Exhaustive — no silent default grouping.
 */
public enum ShadowComparisonCategory {

    EQUIVALENT,
    SHADOW_MORE_PERMISSIVE,
    SHADOW_MORE_RESTRICTIVE,
    LEGACY_REVIEW_SHADOW_HOLD,
    NO_SAFE_EQUIVALENCE,
    NOT_COMPARABLE
}