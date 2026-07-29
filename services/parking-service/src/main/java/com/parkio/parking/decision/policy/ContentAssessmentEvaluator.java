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
 * Evaluates CONTENT assessment from AI content evidence.
 * Does not produce PublicationDisposition.
 */
public final class ContentAssessmentEvaluator {

    private final ShadowDecisionPolicyConfig config;

    public ContentAssessmentEvaluator(ShadowDecisionPolicyConfig config) {
        this.config = config;
    }

    public DomainAssessment evaluate(List<EvidenceItem> allItems, EvaluationContext context) {
        List<EvidenceItem> items = EvidenceSelectors.ofType(allItems, EvidenceType.AI_CONTENT_ANALYSIS);
        List<ReasonCode> reasons = new ArrayList<>();
        List<EvidenceItem> contributing = new ArrayList<>();

        Optional<EvidenceItem> failed = EvidenceSelectors.firstWithReason(items, "AI_STATUS_FAILED");
        Optional<EvidenceItem> notParking = EvidenceSelectors.firstWithReason(items, "AI_RISK_NOT_A_PARKING_SPOT");
        Optional<EvidenceItem> warning = EvidenceSelectors.firstWithReason(items, "AI_STATUS_WARNING");
        Optional<EvidenceItem> passed = EvidenceSelectors.firstWithReason(items, "AI_STATUS_PASSED");
        Optional<EvidenceItem> emptySpace = EvidenceSelectors.firstWithReason(items, "EMPTY_SPACE_CONFIDENCE");
        Optional<EvidenceItem> imageQuality = EvidenceSelectors.firstWithReason(items, "IMAGE_QUALITY_SCORE");
        Optional<EvidenceItem> lowQualityRisk = EvidenceSelectors.firstWithReason(items, "AI_RISK_LOW_IMAGE_QUALITY");

        boolean hasStatus = failed.isPresent() || warning.isPresent() || passed.isPresent()
                || EvidenceSelectors.hasReason(items, "AI_STATUS_UNKNOWN");
        int presentSignals = 0;
        if (hasStatus) {
            presentSignals++;
        }
        if (emptySpace.isPresent()) {
            presentSignals++;
        }
        if (imageQuality.isPresent() || lowQualityRisk.isPresent()) {
            presentSignals++;
        }
        AssessmentCompleteness completeness = switch (presentSignals) {
            case 0 -> AssessmentCompleteness.EMPTY;
            case 1, 2 -> AssessmentCompleteness.PARTIAL;
            default -> AssessmentCompleteness.COMPLETE;
        };

        if (items.isEmpty()) {
            return DomainAssessment.of(
                    AssessmentCategory.CONTENT,
                    AssessmentLevel.INSUFFICIENT_EVIDENCE,
                    AssessmentCompleteness.EMPTY,
                    false,
                    List.of(ReasonCode.of("CONTENT_NO_EVIDENCE")),
                    List.of(),
                    context.evaluationPolicyVersion(),
                    context.evaluatedAt());
        }

        AssessmentLevel level;
        if (notParking.isPresent()) {
            level = AssessmentLevel.CRITICAL;
            reasons.add(ReasonCode.of("CONTENT_NOT_PARKING"));
            contributing.add(notParking.get());
        } else if (failed.isPresent()) {
            // Strong risk, not automatic hard constraint / final rejection by itself.
            level = AssessmentLevel.CONCERNING;
            reasons.add(ReasonCode.of("CONTENT_AI_FAILED"));
            contributing.add(failed.get());
        } else if (lowQualityRisk.isPresent()
                || (imageQuality.isPresent()
                        && imageQuality.get().strength() <= ShadowDecisionPolicyConfig.IMAGE_QUALITY_WEAK_MAX)) {
            level = AssessmentLevel.INSUFFICIENT_EVIDENCE;
            reasons.add(ReasonCode.of("CONTENT_WEAK_IMAGE_QUALITY"));
            imageQuality.ifPresent(contributing::add);
            lowQualityRisk.ifPresent(contributing::add);
        } else if (warning.isPresent()) {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("CONTENT_AI_WARNING"));
            contributing.add(warning.get());
        } else if (passed.isPresent()) {
            if (emptySpace.isPresent()
                    && emptySpace.get().strength() >= ShadowDecisionPolicyConfig.EMPTY_SPACE_STRONG_MIN) {
                level = AssessmentLevel.POSITIVE;
                reasons.add(ReasonCode.of("CONTENT_STRONG_VACANCY"));
            } else {
                level = AssessmentLevel.ACCEPTABLE;
                reasons.add(ReasonCode.of("CONTENT_AI_PASSED"));
            }
            contributing.add(passed.get());
            emptySpace.ifPresent(contributing::add);
            imageQuality.ifPresent(contributing::add);
        } else {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("CONTENT_AMBIGUOUS"));
            contributing.addAll(items);
        }

        int categoryScore = config.levelRisk(level);
        int confidence = Math.min(100, 40 + presentSignals * 20);
        return DomainAssessment.of(
                AssessmentCategory.CONTENT,
                level,
                OptionalInt.of(categoryScore),
                OptionalInt.of(confidence),
                completeness,
                false,
                reasons,
                EvidenceSelectors.refs(contributing),
                context.evaluationPolicyVersion(),
                context.evaluatedAt());
    }
}