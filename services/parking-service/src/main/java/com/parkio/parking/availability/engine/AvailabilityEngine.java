package com.parkio.parking.availability.engine;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.availability.policy.AvailabilityPolicy;
import com.parkio.parking.availability.policy.AvailabilityPolicyConfig;
import com.parkio.parking.availability.policy.DefaultAvailabilityPolicy;
import java.util.Objects;

/**
 * Pure Availability Engine facade.
 *
 * <p>Does not know about shadow mode, Spring, Kafka, search ranking, or publication mutation.
 */
public final class AvailabilityEngine {

    private final AvailabilityPolicy policy;

    public AvailabilityEngine() {
        this(new DefaultAvailabilityPolicy(AvailabilityPolicyConfig.referenceV1()));
    }

    public AvailabilityEngine(AvailabilityPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public AvailabilityEvaluation evaluate(AvailabilityEvidence evidence, AvailabilityEvaluationContext context) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        return policy.evaluate(evidence, context);
    }
}
