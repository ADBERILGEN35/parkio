package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evaluation.EvaluationContext;

/**
 * Pure decision composition: AssessmentBundle + RiskAssessment → DecisionResult.
 * Does not consume raw EvidenceItem or provider payloads.
 */
public interface DecisionPolicy {

    DecisionResult decide(
            AssessmentBundle assessments, RiskAssessment risk, EvaluationContext context);
}