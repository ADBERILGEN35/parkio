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

/** Evaluates LOCATION from geospatial evidence. Invalid coordinates are critical (hard constraint later). */
public final class LocationAssessmentEvaluator {

    private final ShadowDecisionPolicyConfig config;

    public LocationAssessmentEvaluator(ShadowDecisionPolicyConfig config) {
        this.config = config;
    }

    public DomainAssessment evaluate(List<EvidenceItem> allItems, EvaluationContext context) {
        List<EvidenceItem> items = EvidenceSelectors.ofType(allItems, EvidenceType.GEOSPATIAL_CONSISTENCY);
        if (items.isEmpty()) {
            return DomainAssessment.of(
                    AssessmentCategory.LOCATION,
                    AssessmentLevel.INSUFFICIENT_EVIDENCE,
                    AssessmentCompleteness.EMPTY,
                    false,
                    List.of(ReasonCode.of("LOCATION_NO_EVIDENCE")),
                    List.of(),
                    context.evaluationPolicyVersion(),
                    context.evaluatedAt());
        }

        Optional<EvidenceItem> invalid = EvidenceSelectors.firstWithReason(items, "COORDINATES_INVALID");
        Optional<EvidenceItem> valid = EvidenceSelectors.firstWithReason(items, "COORDINATES_VALID");
        Optional<EvidenceItem> manual = EvidenceSelectors.firstWithReason(items, "MANUAL_LOCATION_EDITED");
        List<ReasonCode> reasons = new ArrayList<>();
        List<EvidenceItem> contributing = new ArrayList<>();

        AssessmentLevel level;
        boolean hard = false;
        if (invalid.isPresent()) {
            level = AssessmentLevel.CRITICAL;
            hard = true;
            reasons.add(ReasonCode.of("LOCATION_COORDINATES_INVALID"));
            contributing.add(invalid.get());
        } else if (valid.isPresent()) {
            level = manual.isPresent() ? AssessmentLevel.ACCEPTABLE : AssessmentLevel.POSITIVE;
            reasons.add(ReasonCode.of(manual.isPresent() ? "LOCATION_VALID_MANUAL_EDIT" : "LOCATION_VALID"));
            contributing.add(valid.get());
            manual.ifPresent(contributing::add);
        } else {
            level = AssessmentLevel.UNCERTAIN;
            reasons.add(ReasonCode.of("LOCATION_AMBIGUOUS"));
            contributing.addAll(items);
        }

        AssessmentCompleteness completeness =
                valid.isPresent() || invalid.isPresent()
                        ? AssessmentCompleteness.COMPLETE
                        : AssessmentCompleteness.PARTIAL;

        return DomainAssessment.of(
                AssessmentCategory.LOCATION,
                level,
                OptionalInt.of(config.levelRisk(level)),
                OptionalInt.of(90),
                completeness,
                hard,
                reasons,
                EvidenceSelectors.refs(contributing),
                context.evaluationPolicyVersion(),
                context.evaluatedAt());
    }
}