package com.parkio.parking.availability.policy;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;

/**
 * Pure availability assessment policy.
 */
public interface AvailabilityPolicy {

    AvailabilityEvaluation evaluate(AvailabilityEvidence evidence, AvailabilityEvaluationContext context);
}