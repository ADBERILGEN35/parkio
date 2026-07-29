package com.parkio.parking.outcome;

import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import java.util.Objects;

/** Immutable replay bundle for outcome validation history. */
public record OutcomeSnapshot(
        OutcomeEvidence evidence,
        OutcomeEvaluationContext context,
        OutcomeEvaluation evaluation) {

    public OutcomeSnapshot {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evidence.parkingSpotId().equals(evaluation.parkingSpotId())) {
            throw new IllegalArgumentException("evidence and evaluation parkingSpotId must match");
        }
        if (!context.evaluatedAt().equals(evaluation.evaluatedAt())) {
            throw new IllegalArgumentException("context and evaluation evaluatedAt must match");
        }
        if (!context.policyVersion().equals(evaluation.policyVersion())) {
            throw new IllegalArgumentException("context and evaluation policyVersion must match");
        }
    }

    public OutcomePolicyVersion policyVersion() {
        return context.policyVersion();
    }
}