package com.parkio.parking.outcome.engine;

import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.policy.DefaultOutcomePolicy;
import com.parkio.parking.outcome.policy.OutcomePolicy;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import java.util.Objects;

/** Pure outcome validation facade. */
public final class OutcomeValidationEngine {

    private final OutcomePolicy policy;

    public OutcomeValidationEngine() {
        this(new DefaultOutcomePolicy(OutcomePolicyConfig.referenceV1()));
    }

    public OutcomeValidationEngine(OutcomePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public OutcomeEvaluation evaluate(OutcomeEvidence evidence, OutcomeEvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        return policy.evaluate(evidence, context);
    }
}