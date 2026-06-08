package com.parkio.aivalidation.domain;

/**
 * Advisory outcome of a validation. Never a final decision (ai-context/02): moderation
 * and humans decide. {@code FAILED} only means the image is clearly unusable or not a
 * parking-related image — it does not reject a spot.
 */
public enum AiValidationStatus {
    PASSED,
    WARNING,
    FAILED
}
