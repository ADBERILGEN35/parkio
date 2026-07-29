package com.parkio.parking.decision.audit;

import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import java.util.Objects;

/**
 * Offline replay input reconstructed from a {@link DecisionAuditRecord}.
 *
 * <p>Contains only canonical decision-domain values required by
 * {@link com.parkio.parking.decision.policy.DecisionEngine#evaluate}.
 */
public final class DecisionReplayInput {

    private final EvidenceVector evidence;
    private final EvaluationContext context;

    private DecisionReplayInput(EvidenceVector evidence, EvaluationContext context) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.context = Objects.requireNonNull(context, "context");
    }

    public static DecisionReplayInput of(EvidenceVector evidence, EvaluationContext context) {
        return new DecisionReplayInput(evidence, context);
    }

    public EvidenceVector evidence() {
        return evidence;
    }

    public EvaluationContext context() {
        return context;
    }
}