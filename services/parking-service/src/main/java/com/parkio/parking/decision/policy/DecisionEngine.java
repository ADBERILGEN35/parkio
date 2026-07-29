package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.port.EvidenceEvaluationPolicy;
import com.parkio.parking.decision.port.RiskAssessmentPolicy;
import java.util.Objects;

/**
 * Pure Decision Engine facade for the complete non-side-effect pipeline:
 * EvidenceVector → AssessmentBundle → RiskAssessment → DecisionResult.
 *
 * <p>Does not know about shadow mode, Spring, Kafka, or publication mutation.
 */
public final class DecisionEngine {

    private final EvidenceEvaluationPolicy evaluationPolicy;
    private final RiskAssessmentPolicy riskPolicy;
    private final DecisionPolicy decisionPolicy;

    public DecisionEngine() {
        ShadowDecisionPolicyConfig config = ShadowDecisionPolicyConfig.referenceV1();
        this.evaluationPolicy = new DefaultEvidenceEvaluationPolicy(config);
        this.riskPolicy = new DefaultRiskAssessmentPolicy(config);
        this.decisionPolicy = new DefaultDecisionPolicy(config);
    }

    public DecisionEngine(
            EvidenceEvaluationPolicy evaluationPolicy,
            RiskAssessmentPolicy riskPolicy,
            DecisionPolicy decisionPolicy) {
        this.evaluationPolicy = Objects.requireNonNull(evaluationPolicy, "evaluationPolicy");
        this.riskPolicy = Objects.requireNonNull(riskPolicy, "riskPolicy");
        this.decisionPolicy = Objects.requireNonNull(decisionPolicy, "decisionPolicy");
    }

    public DecisionResult evaluate(EvidenceVector evidence, EvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        AssessmentBundle bundle = evaluationPolicy.evaluate(evidence, context);
        RiskAssessment risk = riskPolicy.assess(bundle, context);
        return decisionPolicy.decide(bundle, risk, context);
    }

    public AssessmentBundle evaluateAssessments(EvidenceVector evidence, EvaluationContext context) {
        return evaluationPolicy.evaluate(evidence, context);
    }
}