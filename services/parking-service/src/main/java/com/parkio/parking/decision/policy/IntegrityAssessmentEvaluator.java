package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Evaluates INTEGRITY from operational provenance.
 * Media mismatch is critical. Stale event is uncertain/operational, not location invalidity.
 */
public final class IntegrityAssessmentEvaluator {

    private final ShadowDecisionPolicyConfig config;

    public IntegrityAssessmentEvaluator(ShadowDecisionPolicyConfig config) {
        this.config = config;
    }

    public DomainAssessment evaluate(List<EvidenceItem> allItems, EvaluationContext context) {
        List<EvidenceItem> items = EvidenceSelectors.ofType(allItems, EvidenceType.OPERATIONAL_PROVENANCE);
        if (items.isEmpty()) {
            return DomainAssessment.of(
                    AssessmentCategory.INTEGRITY,
                    AssessmentLevel.INSUFFICIENT_EVIDENCE,
                    AssessmentCompleteness.EMPTY,
                    false,
                    List.of(ReasonCode.of("INTEGRITY_NO_EVIDENCE")),
                    List.of(),
                    context.evaluationPolicyVersion(),
                    context.evaluatedAt());
        }

        Optional<EvidenceItem> mismatch = EvidenceSelectors.firstWithReason(items, "MEDIA_SPOT_MISMATCH");
        Optional<EvidenceItem> stale = EvidenceSelectors.firstWithReason(items, "STALE_MODERATION_EVENT");
        Optional<EvidenceItem> correlated = EvidenceSelectors.firstWithReason(items, "AI_EVENT_CORRELATED");
        List<ReasonCode> reasons = new ArrayList<>();
        List<EvidenceItem> contributing = new ArrayList<>();

        AssessmentLevel level;
        boolean hard = false;
        if (mismatch.isPresent()) {
            level = AssessmentLevel.CRITICAL;
            hard = true;
            reasons.add(ReasonCode.of("INTEGRITY_MEDIA_MISMATCH"));
            contributing.add(mismatch.get());
        } else if (stale.isPresent()) {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("INTEGRITY_STALE_EVENT"));
            contributing.add(stale.get());
            correlated.ifPresent(contributing::add);
        } else if (correlated.isPresent()) {
            level = AssessmentLevel.POSITIVE;
            reasons.add(ReasonCode.of("INTEGRITY_CORRELATED"));
            contributing.add(correlated.get());
        } else {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("INTEGRITY_AMBIGUOUS"));
            contributing.addAll(items);
        }

        return DomainAssessment.of(
                AssessmentCategory.INTEGRITY,
                level,
                OptionalInt.of(config.levelRisk(level)),
                OptionalInt.of(85),
                correlated.isPresent() || mismatch.isPresent()
                        ? AssessmentCompleteness.COMPLETE
                        : AssessmentCompleteness.PARTIAL,
                hard,
                reasons,
                EvidenceSelectors.refs(contributing),
                context.evaluationPolicyVersion(),
                context.evaluatedAt());
    }
}