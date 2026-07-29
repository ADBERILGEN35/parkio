package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.policy.HardConstraintResult;
import java.util.Objects;

/** Derives a bounded hard-constraint family from HardConstraintResult. */
public final class HardConstraintFamilyClassifier {

    private HardConstraintFamilyClassifier() {}

    public static HardConstraintFamily from(HardConstraintResult hard) {
        Objects.requireNonNull(hard, "hard");
        if (!hard.active()) {
            return HardConstraintFamily.NONE;
        }
        boolean integrity = hard.reasonCodes().contains(ReasonCode.of("HARD_MEDIA_SPOT_MISMATCH"));
        boolean location = hard.reasonCodes().contains(ReasonCode.of("HARD_COORDINATES_INVALID"));
        if (integrity && !location) {
            return HardConstraintFamily.INTEGRITY;
        }
        if (location && !integrity) {
            return HardConstraintFamily.LOCATION;
        }
        if (integrity && location) {
            return HardConstraintFamily.OTHER;
        }
        return HardConstraintFamily.OTHER;
    }
}
