package com.parkio.parking.decision.port;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.score.AvailabilityScore;
import com.parkio.parking.decision.score.TrustScore;
import java.time.Instant;
import java.util.Optional;

/**
 * Inbound Decision Engine evaluation port (ADR-WP05 {@code DecisionPort}).
 *
 * <p>Evaluates disposition from a fully supplied evidence snapshot, category
 * assessments, and risk assessment. MUST NOT fetch infrastructure data itself.
 * Deterministic for identical inputs, clock instant, and policy version.
 * WP-05.5 provides a pure {@code DecisionPolicy}/{@code DecisionEngine} for non-authoritative shadow evaluation. Production authority remains {@code applyAiValidationResult}.
 */
public interface DecisionPort {

    DecisionResult decide(
            EvidenceVector evidence,
            AssessmentBundle assessments,
            RiskAssessment riskAssessment,
            Optional<TrustScore> trustScore,
            Optional<AvailabilityScore> availabilityScore,
            String policyVersion,
            Instant decidedAt);
}
