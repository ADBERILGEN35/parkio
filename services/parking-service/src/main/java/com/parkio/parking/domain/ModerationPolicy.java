package com.parkio.parking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Tunable moderation-lifecycle policy, supplied by infrastructure from
 * {@code parkio.parking.moderation.*}. A plain value so the domain and application
 * layers carry no dependency on Spring configuration types (mirrors
 * {@code ParkingSearchSettings}).
 *
 * <p>Windows are deliberately independent and sized for perishable parking availability
 * (advertised lifetime ~10 minutes):
 *
 * <ul>
 *   <li>{@code activeDuration} — the <em>advertised</em> user-visible lifetime. Spent only
 *       while the spot is publicly discoverable, never while it waits on moderation.</li>
 *   <li>{@code validationTimeout} — how long the AI publication gate may take before the
 *       spot is re-requested. Bounded by {@code maxValidationAttempts}; each retry backs
 *       off by {@code validationRetryBackoff} multiplied by the attempt number.</li>
 *   <li>{@code reviewTimeout} — how long a human moderator has once the spot lands in
 *       {@code PENDING_REVIEW}. Retrying cannot help here, so breaching it moves to
 *       {@code REVIEW_FAILED}.</li>
 *   <li>{@code maxPublishableAge} — hard ceiling from {@code createdAt}. A spot whose
 *       underlying availability report is older than this is never published, even if a
 *       late approval would otherwise grant a fresh TTL. Required invariant for
 *       time-sensitive parking.</li>
 * </ul>
 */
public record ModerationPolicy(
        Duration activeDuration,
        Duration validationTimeout,
        Duration validationRetryBackoff,
        int maxValidationAttempts,
        Duration reviewTimeout,
        Duration maxPublishableAge) {

    public ModerationPolicy {
        requirePositive(activeDuration, "activeDuration");
        requirePositive(validationTimeout, "validationTimeout");
        requirePositive(validationRetryBackoff, "validationRetryBackoff");
        requirePositive(reviewTimeout, "reviewTimeout");
        requirePositive(maxPublishableAge, "maxPublishableAge");
        if (maxValidationAttempts < 1) {
            throw new IllegalArgumentException("maxValidationAttempts must be at least 1");
        }
        if (maxPublishableAge.compareTo(activeDuration) < 0) {
            throw new IllegalArgumentException(
                    "maxPublishableAge must be at least activeDuration — otherwise a spot "
                            + "could never be published within its own trust window");
        }
    }

    /**
     * Whether the availability report is still fresh enough to publish at {@code now}.
     * Measured from creation (the moment the real-world observation was submitted), not
     * from the moderation decision.
     */
    public boolean isStillPublishable(Instant createdAt, Instant now) {
        return createdAt != null && now != null && now.isBefore(createdAt.plus(maxPublishableAge));
    }

    /** Instant after which publication is refused for a spot created at {@code createdAt}. */
    public Instant publishableUntil(Instant createdAt) {
        return createdAt.plus(maxPublishableAge);
    }

    /** The deadline for the {@code n}-th validation attempt, measured from {@code now}. */
    public Duration validationDeadlineFor(int attemptsAlreadyMade) {
        int attempt = Math.max(0, attemptsAlreadyMade);
        return attempt == 0
                ? validationTimeout
                : validationTimeout.plus(validationRetryBackoff.multipliedBy(attempt));
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be a positive duration");
        }
    }
}
