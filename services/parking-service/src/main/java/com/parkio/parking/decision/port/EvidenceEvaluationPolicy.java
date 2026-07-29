package com.parkio.parking.decision.port;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;

/**
 * Deterministic evidence → domain assessment boundary.
 *
 * <p>Interprets a supplied {@link EvidenceVector} into an {@link AssessmentBundle}.
 * MUST NOT fetch infrastructure data, use the system clock, select
 * {@code PublicationDisposition}, or mutate ParkingSpot state.
 *
 * <p>No production implementation in WP-05.4 — thresholds lack product grounding.
 * WP-05.5 may introduce a non-authoritative shadow evaluator.
 */
public interface EvidenceEvaluationPolicy {

    AssessmentBundle evaluate(EvidenceVector evidence, EvaluationContext context);
}
