package com.parkio.parking.domain;

/**
 * Lifecycle of a parking spot.
 *
 * <ul>
 *   <li>{@code PENDING_VALIDATION} - created, waiting for AI publication gate.</li>
 *   <li>{@code PENDING_REVIEW} - AI uncertain / warning; not publicly discoverable.</li>
 *   <li>{@code ACTIVE} - AI validation passed; within its validity window.</li>
 *   <li>{@code VERIFIED} - at least one user confirmed it as available.</li>
 *   <li>{@code SUSPICIOUS} - one negative signal (a filled report / low confidence).</li>
 *   <li>{@code FILLED} - taken (claimed) or confirmed full by reports (terminal).</li>
 *   <li>{@code EXPIRED} - validity window elapsed (terminal).</li>
 *   <li>{@code REJECTED} - found illegal/risky or rejected by AI (terminal).</li>
 *   <li>{@code REVIEW_FAILED} - moderation never reached a verdict within its deadline,
 *       or bounded validation retries were exhausted (terminal).</li>
 * </ul>
 *
 * <p>The user-visible lifetime (TTL) is <em>not</em> consumed by the pending statuses:
 * a spot's {@code expiresAt} is computed exactly once, when it is published
 * ({@link ParkingSpot#activatedAt()}), so a slow moderation pipeline can never shorten
 * the advertised visibility window.
 */
public enum ParkingSpotStatus {
    PENDING_VALIDATION,
    PENDING_REVIEW,
    ACTIVE,
    VERIFIED,
    SUSPICIOUS,
    FILLED,
    EXPIRED,
    REJECTED,
    REVIEW_FAILED;

    /** Whether the spot is still waiting on the moderation pipeline (AI or human). */
    public boolean isPendingModeration() {
        return this == PENDING_VALIDATION || this == PENDING_REVIEW;
    }

    /** Whether no further lifecycle transition is possible. */
    public boolean isTerminal() {
        return this == FILLED || this == EXPIRED || this == REJECTED || this == REVIEW_FAILED;
    }
}
