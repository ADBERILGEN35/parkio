package com.parkio.parking.exposure;

import java.util.Objects;

public record ExposureReplayComparison(
        boolean identical,
        String policyVersion,
        ExposureSnapshotSchemaVersion schemaVersion,
        int candidateCount,
        boolean sameTop1,
        String mismatchReason) {

    public ExposureReplayComparison {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (mismatchReason != null) {
            mismatchReason = mismatchReason.trim();
        }
    }
}
