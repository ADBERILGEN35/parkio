package com.parkio.parking.decision.port;

import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evaluation.EvaluationContext;

/**
 * Derives {@link RiskAssessment} from a supplied {@link AssessmentBundle}.
 *
 * <p>Deterministic for identical assessments + evaluation context. MUST NOT fetch
 * infrastructure data or select {@code PublicationDisposition}.
 * No production implementation in WP-05.4.
 */
public interface RiskAssessmentPolicy {

    RiskAssessment assess(AssessmentBundle assessments, EvaluationContext context);
}
