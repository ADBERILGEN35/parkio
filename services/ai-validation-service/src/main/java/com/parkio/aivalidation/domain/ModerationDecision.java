package com.parkio.aivalidation.domain;

/**
 * Three-way automated moderation outcome mapped to the publication gate.
 * Uncertainty must never become {@link #AUTO_REJECT}.
 */
public enum ModerationDecision {
    AUTO_ACCEPT,
    MANUAL_REVIEW,
    AUTO_REJECT
}
