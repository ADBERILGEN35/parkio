package com.parkio.parking.decision.authority;

/**
 * Exhaustive classification for CurrentStatus × PublicationDisposition under
 * controlled authority. No silent permissive default for future enums.
 */
public enum AuthorityTransitionClass {
    APPLY_SUPPORTED,
    NO_OP_IDEMPOTENT,
    LEGACY_ONLY,
    MANUAL_REVIEW_ONLY,
    INVALID_TRANSITION,
    FUTURE_UNSUPPORTED
}