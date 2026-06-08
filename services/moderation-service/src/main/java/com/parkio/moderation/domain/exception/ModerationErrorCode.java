package com.parkio.moderation.domain.exception;

/** Stable, domain-level error codes (mapped to HTTP in presentation). */
public enum ModerationErrorCode {
    MISSING_USER_ID,
    FORBIDDEN,
    CASE_NOT_FOUND,
    APPEAL_NOT_FOUND,
    DUPLICATE_REPORT,
    DUPLICATE_APPEAL,
    CASE_NOT_RESOLVED,
    INVALID_CASE_STATE,
    INVALID_APPEAL_STATE
}
