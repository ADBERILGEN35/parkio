package com.parkio.aivalidation.domain;

/**
 * Product-facing publication decision derived from {@link AiValidationStatus}.
 * Wire enums (PASSED/WARNING/FAILED) are unchanged; this is API clarity only.
 */
public enum AiValidationDecision {
    ACCEPT,
    REVIEW,
    REJECT;

    public static AiValidationDecision from(AiValidationStatus status) {
        return switch (status) {
            case PASSED -> ACCEPT;
            case WARNING -> REVIEW;
            case FAILED -> REJECT;
        };
    }
}
