package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.ModerationDecision;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModerationDecisionPolicyTest {

    private VisionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
    }

    @Test
    void markedBayAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.88, "CLEAR_USABLE_SPACE",
                "FREE", "FITS", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING);
    }

    @Test
    void unmarkedCurbsideGapAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.72, "CLEAR_USABLE_SPACE",
                "UNCERTAIN", "TIGHT", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING);
    }

    @Test
    void nightStreetManualReview() {
        assertDecision("UNCERTAIN", 0.62, "TOO_DARK_OR_BLURRY",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN);
    }

    @Test
    void selfieAutoRejectsAtHighConfidence() {
        assertDecision("NOT_A_PARKING_SPOT", 0.97, "UNRELATED_SUBJECT",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT);
    }

    @Test
    void legalUncertaintyDoesNotAutoReject() {
        assertDecision("NOT_A_PARKING_SPOT", 0.91, "LEGALITY_UNCERTAIN",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN);
    }

    @Test
    void absentBoundingBoxWholeImageNoRegionManualReview() {
        assertDecision("UNCERTAIN", 0.55, "WHOLE_IMAGE_NO_REGION",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN);
    }

    private void assertDecision(
            String modelVerdict,
            double confidence,
            String reasonCode,
            String regionAssessment,
            String vehicleFit,
            ModerationDecision expectedDecision,
            ContentRiskClassifier.Verdict expectedVerdict) {
        VisionProviderClient.VisionAnalysis analysis = new VisionProviderClient.VisionAnalysis(
                modelVerdict,
                confidence,
                reasonCode,
                regionAssessment,
                vehicleFit,
                null,
                null,
                null,
                null);
        ModerationDecisionPolicy.Result result = ModerationDecisionPolicy.evaluate(analysis, properties);
        assertThat(result.decision()).isEqualTo(expectedDecision);
        assertThat(result.verdict()).isEqualTo(expectedVerdict);
    }
}
