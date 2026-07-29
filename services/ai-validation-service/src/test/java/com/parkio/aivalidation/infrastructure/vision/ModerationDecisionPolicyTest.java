package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.ModerationDecision;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Recall-first decision policy: spam/unrelated → reject at high confidence;
 * plausible parking/road context → accept; quality/legality ambiguity → review.
 */
class ModerationDecisionPolicyTest {

    private VisionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
    }

    @Test
    void markedBayAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.88, "CLEAR_USABLE_SPACE",
                "FREE", "FITS", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void unmarkedCurbsideGapAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.72, "CLEAR_USABLE_SPACE",
                "UNCERTAIN", "TIGHT", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void normalStreetPhotoAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.58, "EMPTY_SPACE_VISIBLE",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void visibleEmptyRoadsideAutoAcceptsDespiteFitUncertainty() {
        // Model unsure whether two cars fit — still clearly parking-related.
        assertDecision("LIKELY_PARKING", 0.61, "POSSIBLE_SPACE_UNCERTAIN_WIDTH",
                "FREE", "UNCERTAIN", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void parkingLotAutoAccepts() {
        assertDecision("LIKELY_PARKING", 0.70, "CLEAR_USABLE_SPACE",
                "FREE", "FITS", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void wholeImageParkingContextAutoAccepts() {
        assertDecision("UNCERTAIN", 0.55, "WHOLE_IMAGE_NO_REGION",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void possibleSpaceSoftRejectStillAutoAccepts() {
        assertDecision("NOT_A_PARKING_SPOT", 0.90, "POSSIBLE_SPACE_UNCLEAR_ACCESS",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.AUTO_ACCEPT, ContentRiskClassifier.Verdict.LIKELY_PARKING,
                null);
    }

    @Test
    void blurryButParkingRelatedGoesToManualReview() {
        assertDecision("UNCERTAIN", 0.62, "TOO_DARK_OR_BLURRY",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void nightStreetManualReview() {
        assertDecision("UNCERTAIN", 0.62, "TOO_DARK_OR_BLURRY",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void selfieAutoRejectsAtHighConfidence() {
        assertDecision("NOT_A_PARKING_SPOT", 0.97, "UNRELATED_SUBJECT",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "CLEARLY_UNRELATED_CONTENT");
    }

    @Test
    void indoorSceneAutoRejectsAtHighConfidence() {
        assertDecision("NOT_A_PARKING_SPOT", 0.96, "INDOOR_SCENE",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "INDOOR_SCENE");
    }

    @Test
    void foodPhotoAutoRejectsAtHighConfidence() {
        assertDecision("NOT_A_PARKING_SPOT", 0.98, "FOOD_OR_RANDOM_OBJECT",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "FOOD_OR_RANDOM_OBJECT");
    }

    @Test
    void selfieReasonCodeAutoRejectsWithProductCode() {
        assertDecision("NOT_A_PARKING_SPOT", 0.97, "SELFIE_OR_PERSONAL_PHOTO",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "SELFIE_OR_PERSONAL_PHOTO");
    }

    @Test
    void screenshotAutoRejectsAtHighConfidence() {
        assertDecision("NOT_A_PARKING_SPOT", 0.97, "SCREENSHOT_OR_SYNTHETIC",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "SCREENSHOT_OR_DOCUMENT");
    }

    @Test
    void weakUnrelatedDoesNotAutoReject() {
        assertDecision("NOT_A_PARKING_SPOT", 0.70, "UNRELATED_SUBJECT",
                null, null, ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void unusableBlackOrBlurryAtHighConfidenceAutoRejects() {
        assertDecision("NOT_A_PARKING_SPOT", 0.96, "TOO_DARK_OR_BLURRY",
                null, null, ModerationDecision.AUTO_REJECT, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT,
                "UNUSABLE_IMAGE");
    }

    @Test
    void legalUncertaintyDoesNotAutoReject() {
        assertDecision("NOT_A_PARKING_SPOT", 0.91, "LEGALITY_UNCERTAIN",
                "UNCERTAIN", "UNCERTAIN", ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void opaqueHardRejectWithoutParkingSignalsStaysManualReview() {
        assertDecision("NOT_A_PARKING_SPOT", 0.99, "OTHER",
                null, null, ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void noPlausibleSpaceStaysManualReview() {
        assertDecision("NOT_A_PARKING_SPOT", 0.99, "NO_PLAUSIBLE_SPACE",
                null, null, ModerationDecision.MANUAL_REVIEW, ContentRiskClassifier.Verdict.UNCERTAIN,
                null);
    }

    @Test
    void mapsProviderUnrelatedSubjectToProductClearlyUnrelated() {
        assertThat(ModerationDecisionPolicy.mapRejectionReasonCode("UNRELATED_SUBJECT"))
                .isEqualTo("CLEARLY_UNRELATED_CONTENT");
    }

    @Test
    void mapsProviderScreenshotSyntheticToProductScreenshotOrDocument() {
        assertThat(ModerationDecisionPolicy.mapRejectionReasonCode("SCREENSHOT_OR_SYNTHETIC"))
                .isEqualTo("SCREENSHOT_OR_DOCUMENT");
    }

    @Test
    void unknownProviderReasonUsesControlledFallbackNeverArbitraryPropagation() {
        assertThat(ModerationDecisionPolicy.mapRejectionReasonCode("GEMINI_WEIRD_INTERNAL_42"))
                .isEqualTo("CLEARLY_UNRELATED_CONTENT");
        assertThat(ModerationDecisionPolicy.mapRejectionReasonCode("totally-made-up"))
                .isEqualTo("CLEARLY_UNRELATED_CONTENT");
    }

    private void assertDecision(
            String modelVerdict,
            double confidence,
            String reasonCode,
            String regionAssessment,
            String vehicleFit,
            ModerationDecision expectedDecision,
            ContentRiskClassifier.Verdict expectedVerdict,
            String expectedRejectionReasonCode) {
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
        assertThat(result.rejectionReasonCode()).isEqualTo(expectedRejectionReasonCode);
    }
}
