package com.parkio.aivalidation.domain;

/**
 * Three-way automated moderation outcome mapped to the publication gate.
 *
 * <p>Spam-filter first: {@link #AUTO_REJECT} only for extremely high-confidence
 * irrelevant/unusable content. Prefer {@link #AUTO_ACCEPT} when the image plausibly
 * shows parking/road context. Uncertainty must never become {@link #AUTO_REJECT}.
 */
public enum ModerationDecision {
    AUTO_ACCEPT,
    MANUAL_REVIEW,
    AUTO_REJECT
}
