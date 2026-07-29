package com.parkio.parking.exposure;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExposureSnapshot(
        String policyVersion,
        ExposureSnapshotSchemaVersion schemaVersion,
        ExposureQueryContext queryContext,
        ExposureEvaluationContext evaluationContext,
        List<ExposureEvidence> candidates,
        List<ExposureEvaluation> evaluations,
        ExposureComparison comparison,
        Instant capturedAt) {

    public ExposureSnapshot {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(queryContext, "queryContext");
        Objects.requireNonNull(evaluationContext, "evaluationContext");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(evaluations, "evaluations");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(capturedAt, "capturedAt");
        candidates = List.copyOf(candidates);
        evaluations = List.copyOf(evaluations);
    }
}
