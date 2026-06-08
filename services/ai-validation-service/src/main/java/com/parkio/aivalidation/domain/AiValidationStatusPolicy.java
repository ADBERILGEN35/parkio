package com.parkio.aivalidation.domain;

import java.util.Collection;

/**
 * Derives the advisory {@link AiValidationStatus} from scores and detected risks
 * (ai-context/02). This is the one place the PASSED/WARNING/FAILED rule lives.
 *
 * <p>The thresholds below are placeholder defaults; real, environment-tunable
 * thresholds are backlog (kept here, not in {@code domain}, only because no real model
 * is wired yet). The status is <strong>advisory</strong>: {@code FAILED} never rejects
 * a spot — moderation decides.
 */
public final class AiValidationStatusPolicy {

    /** Below this image quality the photo is treated as clearly unusable → FAILED. */
    static final int QUALITY_UNUSABLE_BELOW = 25;
    /** Below this image quality the photo is usable but poor → at least WARNING. */
    static final int QUALITY_ACCEPTABLE_BELOW = 60;
    /** At/above this legal-risk score the result is at least WARNING. */
    static final int LEGAL_RISK_WARNING_AT = 50;
    /** Below this AI confidence the result is at least WARNING. */
    static final int CONFIDENCE_ACCEPTABLE_BELOW = 50;

    private AiValidationStatusPolicy() {
    }

    public static AiValidationStatus evaluate(int legalRiskScore, int imageQualityScore,
                                              int aiConfidence, Collection<AiRiskType> detectedRiskTypes) {
        Score.require("legalRiskScore", legalRiskScore);
        Score.require("imageQualityScore", imageQualityScore);
        Score.require("aiConfidence", aiConfidence);

        // FAILED: clearly unusable image, or not a parking-related image at all.
        if (detectedRiskTypes.contains(AiRiskType.NOT_A_PARKING_SPOT)
                || imageQualityScore < QUALITY_UNUSABLE_BELOW) {
            return AiValidationStatus.FAILED;
        }

        // WARNING: legal/placement risk, poor (but usable) quality, or low confidence.
        boolean legalRisk = legalRiskScore >= LEGAL_RISK_WARNING_AT
                || detectedRiskTypes.stream().anyMatch(AiRiskType::isLegalRisk);
        boolean qualityIssue = imageQualityScore < QUALITY_ACCEPTABLE_BELOW
                || detectedRiskTypes.contains(AiRiskType.LOW_IMAGE_QUALITY);
        boolean lowConfidence = aiConfidence < CONFIDENCE_ACCEPTABLE_BELOW;
        if (legalRisk || qualityIssue || lowConfidence) {
            return AiValidationStatus.WARNING;
        }

        return AiValidationStatus.PASSED;
    }
}
