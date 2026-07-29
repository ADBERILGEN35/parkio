package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.policy.DecisionEngine;
import java.util.Objects;

/**
 * Pure offline comparison of two DecisionEngine evaluations for the same evidence snapshot.
 * Not invoked on the production hot path in WP-05.6.
 */
public final class OfflineDecisionComparison {

    private OfflineDecisionComparison() {}

    public static Diff compare(
            EvidenceVector evidence,
            EvaluationContext context,
            DecisionEngine baseline,
            DecisionEngine candidate) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        DecisionResult left = baseline.evaluate(evidence, context);
        DecisionResult right = candidate.evaluate(evidence, context);
        return Diff.of(left, right);
    }

    public static Diff of(DecisionResult baseline, DecisionResult candidate) {
        return Diff.of(baseline, candidate);
    }

    public record Diff(
            PublicationDisposition baselineDisposition,
            PublicationDisposition candidateDisposition,
            DecisivePolicyRule baselineRule,
            DecisivePolicyRule candidateRule,
            RiskBand baselineRiskBand,
            RiskBand candidateRiskBand,
            boolean dispositionChanged,
            boolean ruleChanged,
            boolean riskBandChanged) {

        static Diff of(DecisionResult baseline, DecisionResult candidate) {
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(candidate, "candidate");
            RiskBand leftBand = baseline.assessment().riskAssessment()
                    .map(RiskBandClassifier::from)
                    .orElse(RiskBand.UNKNOWN);
            RiskBand rightBand = candidate.assessment().riskAssessment()
                    .map(RiskBandClassifier::from)
                    .orElse(RiskBand.UNKNOWN);
            return new Diff(
                    baseline.disposition(),
                    candidate.disposition(),
                    baseline.decisiveRule(),
                    candidate.decisiveRule(),
                    leftBand,
                    rightBand,
                    baseline.disposition() != candidate.disposition(),
                    baseline.decisiveRule() != candidate.decisiveRule(),
                    leftBand != rightBand);
        }
    }
}
