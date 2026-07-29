package com.parkio.parking.exposure;

import java.time.Instant;
import java.util.Objects;

public record ExposureEvaluationContext(
        Instant evaluatedAt,
        String policyVersion,
        ExposureSnapshotSchemaVersion snapshotSchemaVersion) {

    public ExposureEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
    }
}
