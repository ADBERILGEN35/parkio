package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.EvidenceReference;
import com.parkio.parking.decision.assessment.ReasonCode;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates definite hard constraints from AssessmentBundle (not raw Kafka data).
 * Initial constraints: MEDIA_SPOT_MISMATCH, COORDINATES_INVALID.
 */
public final class HardConstraintPolicy {

    private final ShadowDecisionPolicyConfig config;

    public HardConstraintPolicy(ShadowDecisionPolicyConfig config) {
        this.config = config;
    }

    public HardConstraintResult evaluate(AssessmentBundle bundle) {
        List<ReasonCode> reasons = new ArrayList<>();
        List<EvidenceReference> refs = new ArrayList<>();

        bundle.find(AssessmentCategory.INTEGRITY).ifPresent(assessment -> {
            if (assessment.hardConstraint() || hasReason(assessment, "INTEGRITY_MEDIA_MISMATCH")) {
                reasons.add(ReasonCode.of("HARD_MEDIA_SPOT_MISMATCH"));
                refs.addAll(assessment.evidenceReferences());
            }
        });
        bundle.find(AssessmentCategory.LOCATION).ifPresent(assessment -> {
            if (assessment.hardConstraint() || hasReason(assessment, "LOCATION_COORDINATES_INVALID")) {
                reasons.add(ReasonCode.of("HARD_COORDINATES_INVALID"));
                refs.addAll(assessment.evidenceReferences());
            }
        });

        if (reasons.isEmpty()) {
            return HardConstraintResult.inactive(config.policyVersion());
        }
        return HardConstraintResult.active(reasons, refs, config.policyVersion());
    }

    private static boolean hasReason(DomainAssessment assessment, String reason) {
        ReasonCode code = ReasonCode.of(reason);
        return assessment.reasonCodes().contains(code);
    }
}