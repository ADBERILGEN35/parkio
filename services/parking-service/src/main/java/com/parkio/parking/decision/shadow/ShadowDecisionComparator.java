package com.parkio.parking.decision.shadow;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;

/**
 * Exhaustive legacy status ↔ shadow disposition comparison matrix.
 * Outside the pure DecisionEngine — used only for shadow observability.
 */
public final class ShadowDecisionComparator {

    private ShadowDecisionComparator() {}

    public static ShadowComparisonCategory compare(
            ParkingSpotStatus legacyStatus,
            PublicationDisposition shadow,
            LegacyPublicationOutcome.Kind kind) {
        if (kind == LegacyPublicationOutcome.Kind.STALE) {
            // Stale ignore is not a new publication outcome.
            return switch (shadow) {
                case HOLD, SHADOW -> ShadowComparisonCategory.NOT_COMPARABLE;
                case FULL_PUBLISH, REJECTED, LIMITED_PUBLISH, EXPIRED ->
                        ShadowComparisonCategory.NOT_COMPARABLE;
            };
        }
        return compareStatus(legacyStatus, shadow);
    }

    public static ShadowComparisonCategory compareStatus(
            ParkingSpotStatus legacyStatus, PublicationDisposition shadow) {
        return switch (legacyStatus) {
            case ACTIVE, VERIFIED -> switch (shadow) {
                case FULL_PUBLISH -> ShadowComparisonCategory.EQUIVALENT;
                case HOLD, REJECTED, SHADOW, EXPIRED -> ShadowComparisonCategory.SHADOW_MORE_RESTRICTIVE;
                case LIMITED_PUBLISH -> ShadowComparisonCategory.NO_SAFE_EQUIVALENCE;
            };
            case PENDING_VALIDATION -> switch (shadow) {
                case HOLD -> ShadowComparisonCategory.EQUIVALENT;
                case FULL_PUBLISH -> ShadowComparisonCategory.SHADOW_MORE_PERMISSIVE;
                case REJECTED, SHADOW, EXPIRED -> ShadowComparisonCategory.SHADOW_MORE_RESTRICTIVE;
                case LIMITED_PUBLISH -> ShadowComparisonCategory.NO_SAFE_EQUIVALENCE;
            };
            case PENDING_REVIEW -> switch (shadow) {
                case HOLD -> ShadowComparisonCategory.LEGACY_REVIEW_SHADOW_HOLD;
                case FULL_PUBLISH -> ShadowComparisonCategory.SHADOW_MORE_PERMISSIVE;
                case REJECTED, SHADOW, EXPIRED -> ShadowComparisonCategory.SHADOW_MORE_RESTRICTIVE;
                case LIMITED_PUBLISH -> ShadowComparisonCategory.NO_SAFE_EQUIVALENCE;
            };
            case REJECTED -> switch (shadow) {
                case REJECTED -> ShadowComparisonCategory.EQUIVALENT;
                case HOLD, SHADOW -> ShadowComparisonCategory.SHADOW_MORE_PERMISSIVE;
                case FULL_PUBLISH, LIMITED_PUBLISH -> ShadowComparisonCategory.SHADOW_MORE_PERMISSIVE;
                case EXPIRED -> ShadowComparisonCategory.NO_SAFE_EQUIVALENCE;
            };
            case EXPIRED -> switch (shadow) {
                case EXPIRED -> ShadowComparisonCategory.EQUIVALENT;
                case HOLD, REJECTED, SHADOW, FULL_PUBLISH, LIMITED_PUBLISH ->
                        ShadowComparisonCategory.NO_SAFE_EQUIVALENCE;
            };
            case REVIEW_FAILED -> switch (shadow) {
                case HOLD, SHADOW, REJECTED, EXPIRED, FULL_PUBLISH, LIMITED_PUBLISH ->
                        ShadowComparisonCategory.NOT_COMPARABLE;
            };
            case SUSPICIOUS, FILLED -> switch (shadow) {
                case FULL_PUBLISH, HOLD, REJECTED, SHADOW, LIMITED_PUBLISH, EXPIRED ->
                        ShadowComparisonCategory.NOT_COMPARABLE;
            };
        };
    }
}