package com.parkio.parking.exposure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExposureComparison(
        String policyVersion,
        ExposureSnapshotSchemaVersion schemaVersion,
        ExposureQueryContext queryContext,
        int candidateCount,
        boolean sameTop1,
        boolean sameTop3Order,
        boolean sameTop3Set,
        int meanAbsoluteRankMovement,
        int maxRankMovement,
        int promotedCount,
        int demotedCount,
        int ineligibleInLegacyCount,
        String movementBand,
        List<LegacySearchPosition> legacyOrder,
        List<ShadowExposurePosition> shadowOrder,
        Instant comparedAt) {

    public ExposureComparison {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(movementBand, "movementBand");
        Objects.requireNonNull(legacyOrder, "legacyOrder");
        Objects.requireNonNull(shadowOrder, "shadowOrder");
        Objects.requireNonNull(comparedAt, "comparedAt");
        legacyOrder = List.copyOf(legacyOrder);
        shadowOrder = List.copyOf(shadowOrder);
    }
}
