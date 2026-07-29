package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.assessment.DerivedAssessment;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Conservative decision-shadow-v1 composer.
 *
 * <p>Order: hard constraints → critical finality → insufficient evidence → elevated risk → FULL.
 * Prefers HOLD over REJECTED when finality is not justified. AI failure alone is not REJECTED.
 */
public final class DefaultDecisionPolicy implements DecisionPolicy {

    private final ShadowDecisionPolicyConfig config;
    private final HardConstraintPolicy hardConstraints;

    public DefaultDecisionPolicy() {
        this(ShadowDecisionPolicyConfig.referenceV1());
    }

    public DefaultDecisionPolicy(ShadowDecisionPolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.hardConstraints = new HardConstraintPolicy(config);
    }

    @Override
    public DecisionResult decide(
            AssessmentBundle assessments, RiskAssessment risk, EvaluationContext context) {
        Objects.requireNonNull(assessments, "assessments");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(context, "context");
        if (!ShadowDecisionPolicyConfig.POLICY_VERSION.equals(context.evaluationPolicyVersion())) {
            throw new IllegalArgumentException(
                    "unsupported evaluation policy version: " + context.evaluationPolicyVersion().value());
        }

        HardConstraintResult hard = hardConstraints.evaluate(assessments);
        LinkedHashSet<ReasonCode> reasons = new LinkedHashSet<>();
        PublicationDisposition disposition;
        DecisivePolicyRule decisiveRule;

        if (hard.active() && hard.reasonCodes().contains(ReasonCode.of("HARD_MEDIA_SPOT_MISMATCH"))) {
            disposition = PublicationDisposition.SHADOW;
            decisiveRule = DecisivePolicyRule.HARD_MEDIA_MISMATCH;
            reasons.add(ReasonCode.of("DECISION_HARD_MEDIA_MISMATCH"));
            reasons.addAll(hard.reasonCodes());
        } else if (hard.active() && hard.reasonCodes().contains(ReasonCode.of("HARD_COORDINATES_INVALID"))) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.HARD_INVALID_COORDINATES;
            reasons.add(ReasonCode.of("DECISION_HARD_INVALID_COORDINATES"));
            reasons.addAll(hard.reasonCodes());
        } else if (isFinalContentInvalidity(assessments)) {
            disposition = PublicationDisposition.REJECTED;
            decisiveRule = DecisivePolicyRule.CRITICAL_NOT_PARKING;
            reasons.add(ReasonCode.of("DECISION_NOT_A_PARKING_SPOT"));
        } else if (hasInsufficientRequired(assessments, reasons)) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.INSUFFICIENT_CONTENT;
            reasons.add(ReasonCode.of("DECISION_INSUFFICIENT_EVIDENCE"));
        } else if (isLegalityConflict(assessments, reasons)) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.LEGALITY_CONCERN;
            reasons.add(ReasonCode.of("DECISION_UNRESOLVED_CONFLICT"));
        } else if (hasUnresolvedContentUncertainty(assessments, reasons)) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.UNRESOLVED_CONFLICT;
            reasons.add(ReasonCode.of("DECISION_UNRESOLVED_CONFLICT"));
        } else if (risk.score().isPresent()
                && risk.score().get().value() >= ShadowDecisionPolicyConfig.RISK_HIGH_MIN) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.HIGH_RISK;
            reasons.add(ReasonCode.of("DECISION_HIGH_RISK"));
        } else if (risk.score().isPresent()
                && risk.score().get().value() >= ShadowDecisionPolicyConfig.RISK_ELEVATED_MIN) {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.ELEVATED_RISK;
            reasons.add(ReasonCode.of("DECISION_ELEVATED_RISK"));
        } else if (requiredCategoriesComplete(assessments)
                && risk.score().isPresent()
                && risk.score().get().value() <= ShadowDecisionPolicyConfig.RISK_FULL_PUBLISH_MAX) {
            disposition = PublicationDisposition.FULL_PUBLISH;
            decisiveRule = DecisivePolicyRule.LOW_RISK_COMPLETE;
            reasons.add(ReasonCode.of("DECISION_LOW_RISK_COMPLETE"));
        } else {
            disposition = PublicationDisposition.HOLD;
            decisiveRule = DecisivePolicyRule.FALLBACK_HOLD;
            reasons.add(ReasonCode.of("DECISION_CONSERVATIVE_HOLD"));
        }

        if (reasons.isEmpty()) {
            reasons.add(ReasonCode.of("DECISION_DEFAULT"));
        }

        DerivedAssessment derived = DerivedAssessment.of(
                Optional.of(assessments),
                Optional.of(risk),
                List.copyOf(reasons),
                config.policyVersion(),
                context.evaluatedAt());

        return DecisionResult.of(
                assessments.parkingSpotId(),
                assessments.evaluationId(),
                disposition,
                derived,
                List.copyOf(reasons),
                decisiveRule,
                config.policyVersion().value(),
                context.evaluatedAt(),
                disposition == PublicationDisposition.HOLD);
    }

    private static boolean isFinalContentInvalidity(AssessmentBundle assessments) {
        return assessments
                .find(AssessmentCategory.CONTENT)
                .filter(a -> a.level() == AssessmentLevel.CRITICAL)
                .filter(a -> a.reasonCodes().contains(ReasonCode.of("CONTENT_NOT_PARKING")))
                .isPresent();
    }

    private static boolean hasInsufficientRequired(
            AssessmentBundle assessments, LinkedHashSet<ReasonCode> reasons) {
        Optional<DomainAssessment> content = assessments.find(AssessmentCategory.CONTENT);
        if (content.isPresent() && content.get().level() == AssessmentLevel.INSUFFICIENT_EVIDENCE) {
            reasons.addAll(content.get().reasonCodes());
            return true;
        }
        Optional<DomainAssessment> location = assessments.find(AssessmentCategory.LOCATION);
        if (location.isPresent() && location.get().level() == AssessmentLevel.INSUFFICIENT_EVIDENCE) {
            reasons.addAll(location.get().reasonCodes());
            return true;
        }
        return false;
    }

    private static boolean isLegalityConflict(
            AssessmentBundle assessments, LinkedHashSet<ReasonCode> reasons) {
        Optional<DomainAssessment> content = assessments.find(AssessmentCategory.CONTENT);
        Optional<DomainAssessment> legality = assessments.find(AssessmentCategory.LEGALITY);
        boolean contentPositive = content.isPresent()
                && (content.get().level() == AssessmentLevel.POSITIVE
                        || content.get().level() == AssessmentLevel.ACCEPTABLE);
        boolean legalityConcern = legality.isPresent()
                && (legality.get().level() == AssessmentLevel.CONCERNING
                        || legality.get().level() == AssessmentLevel.CRITICAL);
        if (contentPositive && legalityConcern) {
            reasons.add(ReasonCode.of("DECISION_CONTENT_LEGALITY_CONFLICT"));
            return true;
        }
        return false;
    }

    private static boolean hasUnresolvedContentUncertainty(
            AssessmentBundle assessments, LinkedHashSet<ReasonCode> reasons) {
        Optional<DomainAssessment> content = assessments.find(AssessmentCategory.CONTENT);
        if (content.isPresent() && content.get().level() == AssessmentLevel.UNCERTAIN) {
            reasons.addAll(content.get().reasonCodes());
            return true;
        }
        return false;
    }

    private static boolean requiredCategoriesComplete(AssessmentBundle assessments) {
        for (AssessmentCategory category : List.of(
                AssessmentCategory.CONTENT,
                AssessmentCategory.LEGALITY,
                AssessmentCategory.LOCATION,
                AssessmentCategory.INTEGRITY)) {
            Optional<DomainAssessment> assessment = assessments.find(category);
            if (assessment.isEmpty()) {
                return false;
            }
            AssessmentLevel level = assessment.get().level();
            if (level == AssessmentLevel.INSUFFICIENT_EVIDENCE
                    || level == AssessmentLevel.CRITICAL
                    || level == AssessmentLevel.CONCERNING) {
                return false;
            }
        }
        return true;
    }
}