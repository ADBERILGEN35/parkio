package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.EvidenceReference;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.port.RiskAssessmentPolicy;
import com.parkio.parking.decision.score.RiskScore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic reference RiskAssessmentPolicy for decision-shadow-v1.
 * Integer weighted aggregation; hard constraints remain independently visible.
 */
public final class DefaultRiskAssessmentPolicy implements RiskAssessmentPolicy {

    private final ShadowDecisionPolicyConfig config;
    private final HardConstraintPolicy hardConstraints;

    public DefaultRiskAssessmentPolicy() {
        this(ShadowDecisionPolicyConfig.referenceV1());
    }

    public DefaultRiskAssessmentPolicy(ShadowDecisionPolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.hardConstraints = new HardConstraintPolicy(config);
    }

    @Override
    public RiskAssessment assess(AssessmentBundle assessments, EvaluationContext context) {
        Objects.requireNonNull(assessments, "assessments");
        Objects.requireNonNull(context, "context");
        requireSupportedVersion(context);

        HardConstraintResult hard = hardConstraints.evaluate(assessments);
        List<ReasonCode> reasons = new ArrayList<>();
        LinkedHashSet<EvidenceReference> refs = new LinkedHashSet<>();

        int weightedSum = 0;
        int weightTotal = 0;
        for (AssessmentCategory category : List.of(
                AssessmentCategory.CONTENT,
                AssessmentCategory.LEGALITY,
                AssessmentCategory.LOCATION,
                AssessmentCategory.INTEGRITY)) {
            Optional<DomainAssessment> optional = assessments.find(category);
            if (optional.isEmpty()) {
                continue;
            }
            DomainAssessment assessment = optional.get();
            int weight = config.weight(category);
            int contribution = config.levelRisk(assessment.level());
            if (assessment.completeness() == AssessmentCompleteness.EMPTY) {
                contribution = Math.max(contribution, config.levelRisk(
                        com.parkio.parking.decision.assessment.AssessmentLevel.INSUFFICIENT_EVIDENCE));
                reasons.add(ReasonCode.of("RISK_EMPTY_" + category.name()));
            }
            weightedSum += weight * contribution;
            weightTotal += weight;
            refs.addAll(assessment.evidenceReferences());
            reasons.addAll(assessment.reasonCodes());
        }

        if (weightTotal == 0) {
            reasons.add(ReasonCode.of("RISK_NO_ACTIVE_CATEGORIES"));
            return RiskAssessment.of(
                    Optional.empty(),
                    reasons,
                    config.policyVersion(),
                    context.evaluatedAt(),
                    hard.active(),
                    List.copyOf(refs));
        }

        int riskValue = ShadowDecisionPolicyConfig.clampRisk(
                ShadowDecisionPolicyConfig.divideHalfUp(weightedSum, weightTotal));
        if (hard.active()) {
            reasons.addAll(hard.reasonCodes());
            refs.addAll(hard.contributingEvidence());
            reasons.add(ReasonCode.of("RISK_HARD_CONSTRAINT_ACTIVE"));
        } else {
            reasons.add(ReasonCode.of("RISK_WEIGHTED_V1"));
        }

        return RiskAssessment.of(
                Optional.of(RiskScore.of(riskValue)),
                List.copyOf(new LinkedHashSet<>(reasons)),
                config.policyVersion(),
                context.evaluatedAt(),
                hard.active(),
                List.copyOf(refs));
    }

    private void requireSupportedVersion(EvaluationContext context) {
        if (!ShadowDecisionPolicyConfig.POLICY_VERSION.equals(context.evaluationPolicyVersion())) {
            throw new IllegalArgumentException(
                    "unsupported evaluation policy version: " + context.evaluationPolicyVersion().value());
        }
    }
}