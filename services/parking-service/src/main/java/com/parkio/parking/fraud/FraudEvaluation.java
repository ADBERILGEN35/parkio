package com.parkio.parking.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable fraud evaluation result. */
public record FraudEvaluation(
        FraudSubject subject,
        FraudDomain domain,
        List<FraudAssessment> assessments,
        Optional<FraudHardAnomalyType> hardAnomaly,
        FraudRiskScore riskScore,
        FraudRiskBand riskBand,
        FraudConfidenceBand confidenceBand,
        FraudEvidenceVolume evidenceVolume,
        FraudDisposition disposition,
        String decisiveRule,
        String policyVersion,
        Instant evaluatedAt,
        Instant windowStart,
        Instant windowEnd) {

    public FraudEvaluation {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(assessments, "assessments");
        Objects.requireNonNull(hardAnomaly, "hardAnomaly");
        Objects.requireNonNull(riskScore, "riskScore");
        Objects.requireNonNull(riskBand, "riskBand");
        Objects.requireNonNull(confidenceBand, "confidenceBand");
        Objects.requireNonNull(evidenceVolume, "evidenceVolume");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(decisiveRule, "decisiveRule");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        assessments = List.copyOf(assessments);
    }
}
