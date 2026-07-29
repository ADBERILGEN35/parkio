package com.parkio.parking.application.calibration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trust evaluation paired with durable outcome label material for calibration. */
public record TrustOutcomeCalibrationPair(
        UUID trustEvaluationId,
        UUID sourceOutcomeId,
        String trustPolicyVersion,
        String trustLevelBand,
        String trustConfidenceBand,
        String outcomeClassification,
        String outcomeReason,
        String attributionQuality,
        Instant evaluatedAt,
        Instant labeledAt) {

    public TrustOutcomeCalibrationPair {
        Objects.requireNonNull(trustEvaluationId, "trustEvaluationId");
        Objects.requireNonNull(sourceOutcomeId, "sourceOutcomeId");
        Objects.requireNonNull(trustPolicyVersion, "trustPolicyVersion");
        Objects.requireNonNull(trustLevelBand, "trustLevelBand");
        Objects.requireNonNull(trustConfidenceBand, "trustConfidenceBand");
        Objects.requireNonNull(outcomeClassification, "outcomeClassification");
        Objects.requireNonNull(outcomeReason, "outcomeReason");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(labeledAt, "labeledAt");
    }
}
