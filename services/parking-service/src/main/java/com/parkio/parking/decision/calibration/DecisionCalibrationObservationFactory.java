package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.policy.HardConstraintPolicy;
import com.parkio.parking.decision.policy.HardConstraintResult;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds a DecisionCalibrationObservation from pure domain outputs. */
public final class DecisionCalibrationObservationFactory {

    private static final HardConstraintPolicy HARD_CONSTRAINTS =
            new HardConstraintPolicy(ShadowDecisionPolicyConfig.referenceV1());

    private DecisionCalibrationObservationFactory() {}

    public static DecisionCalibrationObservation from(
            EvidenceVector evidence,
            DecisionResult decision,
            ShadowDecisionComparison comparison,
            Duration orchestrationDuration,
            Instant observedAt) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(orchestrationDuration, "orchestrationDuration");
        Objects.requireNonNull(observedAt, "observedAt");

        LegacyPublicationOutcome legacy = comparison.legacy();
        RiskAssessment risk = decision.assessment().riskAssessment().orElse(null);
        RiskBand riskBand = risk == null ? RiskBand.UNKNOWN : RiskBandClassifier.from(risk);

        HardConstraintResult hard = decision.assessment().assessmentBundle()
                .map(HARD_CONSTRAINTS::evaluate)
                .orElse(HardConstraintResult.inactive(ShadowDecisionPolicyConfig.POLICY_VERSION));
        HardConstraintFamily family = HardConstraintFamilyClassifier.from(hard);

        List<AssessmentCategorySnapshot> snapshots = new ArrayList<>();
        decision.assessment().assessmentBundle().ifPresent(bundle -> addActive(bundle, snapshots));

        return DecisionCalibrationObservation.of(
                decision.policyVersion(),
                legacy.kind(),
                legacy.resultingStatus(),
                decision.disposition(),
                comparison.category(),
                riskBand,
                family,
                decision.decisiveRule(),
                EvidenceAvailabilityClassifier.from(evidence),
                snapshots,
                orchestrationDuration,
                observedAt);
    }

    private static void addActive(AssessmentBundle bundle, List<AssessmentCategorySnapshot> snapshots) {
        for (AssessmentCategory category : List.of(
                AssessmentCategory.CONTENT,
                AssessmentCategory.LEGALITY,
                AssessmentCategory.LOCATION,
                AssessmentCategory.INTEGRITY)) {
            bundle.find(category).ifPresent(assessment -> snapshots.add(toSnapshot(assessment)));
        }
    }

    private static AssessmentCategorySnapshot toSnapshot(DomainAssessment assessment) {
        return new AssessmentCategorySnapshot(
                assessment.category(), assessment.level(), assessment.completeness());
    }
}
