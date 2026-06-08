package com.parkio.aivalidation.domain.exception;

/** Stable, domain-level error codes (mapped to HTTP in presentation). */
public enum AiValidationErrorCode {
    MISSING_USER_ID,
    FORBIDDEN,
    VALIDATION_RESULT_NOT_FOUND,
    INVALID_SCORE
}
