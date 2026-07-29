package com.parkio.parking.application.calibration;

import com.parkio.parking.fraud.FraudConfidenceBand;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudRiskBand;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fraud ledger evaluation candidate for continuous calibration. */
public record FraudLedgerCalibrationCandidate(
        UUID evaluationId,
        UUID sourceOutcomeId,
        String fraudPolicyVersion,
        FraudRiskBand riskBand,
        FraudConfidenceBand confidenceBand,
        FraudDisposition disposition,
        int effectiveEvidenceCount,
        String outcomeClassification,
        Instant evaluatedAt) {

    public FraudLedgerCalibrationCandidate {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(sourceOutcomeId, "sourceOutcomeId");
        Objects.requireNonNull(fraudPolicyVersion, "fraudPolicyVersion");
        Objects.requireNonNull(riskBand, "riskBand");
        Objects.requireNonNull(confidenceBand, "confidenceBand");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(outcomeClassification, "outcomeClassification");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (effectiveEvidenceCount < 0) {
            throw new IllegalArgumentException("effectiveEvidenceCount must be non-negative");
        }
    }
}
