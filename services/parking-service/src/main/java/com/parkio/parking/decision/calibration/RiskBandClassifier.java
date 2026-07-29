package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.decision.score.RiskScore;
import java.util.Objects;
import java.util.Optional;

/** Maps optional RiskScore + hard-constraint flag to a bounded RiskBand. */
public final class RiskBandClassifier {

    private RiskBandClassifier() {}

    public static RiskBand from(RiskAssessment risk) {
        Objects.requireNonNull(risk, "risk");
        if (risk.hardConstraintActive()) {
            return RiskBand.CRITICAL;
        }
        return fromScore(risk.score());
    }

    public static RiskBand fromScore(Optional<RiskScore> score) {
        Objects.requireNonNull(score, "score");
        if (score.isEmpty()) {
            return RiskBand.UNKNOWN;
        }
        int value = score.get().value();
        if (value <= ShadowDecisionPolicyConfig.RISK_FULL_PUBLISH_MAX) {
            return RiskBand.LOW;
        }
        if (value < ShadowDecisionPolicyConfig.RISK_HIGH_MIN) {
            return RiskBand.ELEVATED;
        }
        return RiskBand.HIGH;
    }
}
