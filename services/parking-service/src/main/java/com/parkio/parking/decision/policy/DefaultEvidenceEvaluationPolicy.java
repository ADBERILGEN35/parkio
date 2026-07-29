package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.port.EvidenceEvaluationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates CONTENT/LEGALITY/LOCATION/INTEGRITY evaluators into AssessmentBundle.
 * Reserved categories remain absent. Side-effect free.
 */
public final class DefaultEvidenceEvaluationPolicy implements EvidenceEvaluationPolicy {

    private final ShadowDecisionPolicyConfig config;
    private final ContentAssessmentEvaluator content;
    private final LegalityAssessmentEvaluator legality;
    private final LocationAssessmentEvaluator location;
    private final IntegrityAssessmentEvaluator integrity;

    public DefaultEvidenceEvaluationPolicy() {
        this(ShadowDecisionPolicyConfig.referenceV1());
    }

    public DefaultEvidenceEvaluationPolicy(ShadowDecisionPolicyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.content = new ContentAssessmentEvaluator(config);
        this.legality = new LegalityAssessmentEvaluator(config);
        this.location = new LocationAssessmentEvaluator(config);
        this.integrity = new IntegrityAssessmentEvaluator(config);
    }

    @Override
    public AssessmentBundle evaluate(EvidenceVector evidence, EvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        if (!ShadowDecisionPolicyConfig.POLICY_VERSION.equals(context.evaluationPolicyVersion())) {
            throw new IllegalArgumentException(
                    "unsupported evaluation policy version: " + context.evaluationPolicyVersion().value());
        }

        List<DomainAssessment> assessments = new ArrayList<>(4);
        assessments.add(content.evaluate(evidence.items(), context));
        assessments.add(legality.evaluate(evidence.items(), context));
        assessments.add(location.evaluate(evidence.items(), context));
        assessments.add(integrity.evaluate(evidence.items(), context));

        return AssessmentBundle.of(
                evidence.parkingSpotId(),
                evidence.evaluationId(),
                evidence.schemaVersion(),
                assessments,
                context.evaluationPolicyVersion(),
                context.evaluatedAt(),
                List.of(ReasonCode.of("EVALUATION_SHADOW_V1")));
    }
}