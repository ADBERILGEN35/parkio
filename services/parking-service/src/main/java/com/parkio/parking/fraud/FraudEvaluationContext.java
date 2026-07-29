package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Evaluation context with injected clock time. */
public record FraudEvaluationContext(
        Instant evaluatedAt,
        String policyVersion,
        FraudSnapshotSchemaVersion snapshotSchemaVersion,
        String mappingVersion) {

    public FraudEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
    }
}
