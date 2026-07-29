package com.parkio.parking.outcome.policy;

import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;

public interface OutcomePolicy {

    OutcomeEvaluation evaluate(OutcomeEvidence evidence, OutcomeEvaluationContext context);
}