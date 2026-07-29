package com.parkio.parking.decision.shadow;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.Objects;

/** Immutable result of comparing legacy publication outcome with shadow DecisionResult. */
public final class ShadowDecisionComparison {

    private final LegacyPublicationOutcome legacy;
    private final PublicationDisposition shadowDisposition;
    private final ShadowComparisonCategory category;

    public ShadowDecisionComparison(
            LegacyPublicationOutcome legacy,
            PublicationDisposition shadowDisposition,
            ShadowComparisonCategory category) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.shadowDisposition = Objects.requireNonNull(shadowDisposition, "shadowDisposition");
        this.category = Objects.requireNonNull(category, "category");
    }

    public static ShadowDecisionComparison of(LegacyPublicationOutcome legacy, DecisionResult shadow) {
        Objects.requireNonNull(shadow, "shadow");
        ShadowComparisonCategory category =
                ShadowDecisionComparator.compare(legacy.resultingStatus(), shadow.disposition(), legacy.kind());
        return new ShadowDecisionComparison(legacy, shadow.disposition(), category);
    }

    public LegacyPublicationOutcome legacy() {
        return legacy;
    }

    public PublicationDisposition shadowDisposition() {
        return shadowDisposition;
    }

    public ShadowComparisonCategory category() {
        return category;
    }

    public ParkingSpotStatus legacyStatus() {
        return legacy.resultingStatus();
    }
}