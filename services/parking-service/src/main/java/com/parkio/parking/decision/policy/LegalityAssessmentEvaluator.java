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

/** Evaluates LEGALITY from legal risk scores, placement risks, and submitter legal status. */
public final class LegalityAssessmentEvaluator {

    private final ShadowDecisionPolicyConfig config;

    public LegalityAssessmentEvaluator(ShadowDecisionPolicyConfig config) {
        this.config = config;
    }

    public DomainAssessment evaluate(List<EvidenceItem> allItems, EvaluationContext context) {
        List<EvidenceItem> legalItems = new ArrayList<>();
        for (EvidenceItem item : allItems) {
            Optional<String> reason = item.reasonCode().map(ReasonCode::value);
            if (reason.isPresent() && EvidenceSelectors.isLegalRiskReason(reason.get())) {
                legalItems.add(item);
            } else if (reason.isPresent()
                    && (reason.get().equals("SUBMITTER_LEGAL_OK")
                            || reason.get().equals("SUBMITTER_LEGAL_UNCERTAIN"))) {
                legalItems.add(item);
            }
        }

        if (legalItems.isEmpty()) {
            // Category still evaluated with insufficient evidence when no legality signals exist.
            return DomainAssessment.of(
                    AssessmentCategory.LEGALITY,
                    AssessmentLevel.INSUFFICIENT_EVIDENCE,
                    AssessmentCompleteness.EMPTY,
                    false,
                    List.of(ReasonCode.of("LEGALITY_NO_EVIDENCE")),
                    List.of(),
                    context.evaluationPolicyVersion(),
                    context.evaluatedAt());
        }

        Optional<EvidenceItem> legalRiskScore = EvidenceSelectors.firstWithReason(allItems, "LEGAL_RISK_SCORE");
        Optional<EvidenceItem> submitterRisk = EvidenceSelectors.firstWithReason(allItems, "SUBMITTER_LEGAL_RISK");
        boolean hasPlacementRisk = false;
        List<EvidenceItem> contributing = new ArrayList<>();
        List<ReasonCode> reasons = new ArrayList<>();

        for (EvidenceItem item : legalItems) {
            String reason = item.reasonCode().map(ReasonCode::value).orElse("");
            if (reason.startsWith("AI_RISK_") && EvidenceSelectors.isLegalRiskReason(reason)) {
                hasPlacementRisk = true;
                contributing.add(item);
            }
        }

        AssessmentLevel level;
        if (legalRiskScore.isPresent()
                && legalRiskScore.get().strength() >= ShadowDecisionPolicyConfig.LEGAL_RISK_CRITICAL_MIN) {
            level = AssessmentLevel.CRITICAL;
            reasons.add(ReasonCode.of("LEGALITY_CRITICAL_SCORE"));
            contributing.add(legalRiskScore.get());
        } else if (hasPlacementRisk
                || submitterRisk.isPresent()
                || (legalRiskScore.isPresent()
                        && legalRiskScore.get().strength()
                                >= ShadowDecisionPolicyConfig.LEGAL_RISK_CONCERNING_MIN)) {
            level = AssessmentLevel.CONCERNING;
            reasons.add(ReasonCode.of("LEGALITY_CONCERN"));
            legalRiskScore.ifPresent(contributing::add);
            submitterRisk.ifPresent(contributing::add);
        } else if (EvidenceSelectors.hasReason(allItems, "SUBMITTER_LEGAL_UNCERTAIN")) {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("LEGALITY_UNCERTAIN"));
            EvidenceSelectors.firstWithReason(allItems, "SUBMITTER_LEGAL_UNCERTAIN")
                    .ifPresent(contributing::add);
        } else {
            level = AssessmentLevel.ACCEPTABLE;
            reasons.add(ReasonCode.of("LEGALITY_ACCEPTABLE"));
            contributing.addAll(legalItems);
        }

        // Strong vacancy evidence must not erase legality concern — level already set without vacancy.
        AssessmentCompleteness completeness =
                legalRiskScore.isPresent() || hasPlacementRisk
                        ? AssessmentCompleteness.COMPLETE
                        : AssessmentCompleteness.PARTIAL;

        return DomainAssessment.of(
                AssessmentCategory.LEGALITY,
                level,
                OptionalInt.of(config.levelRisk(level)),
                OptionalInt.of(completeness == AssessmentCompleteness.COMPLETE ? 80 : 55),
                completeness,
                false,
                reasons,
                EvidenceSelectors.refs(contributing),
                context.evaluationPolicyVersion(),
                context.evaluatedAt());
    }
}