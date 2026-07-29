package com.parkio.parking.decision.score;

/**
 * Time-varying availability of a parking opportunity, scale {@code 0..100} (integer).
 *
 * <p>Availability belongs to the opportunity (freshness / remaining TTL), not the actor.
 * Unknown availability MUST be {@code Optional.empty()}, never {@code 0}.
 *
 * <p>Contains no decay algorithm.
 */
public record AvailabilityScore(int value) {

    public static final int MIN = 0;
    public static final int MAX = 100;

    public AvailabilityScore {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "AvailabilityScore must be between " + MIN + " and " + MAX + " inclusive, got " + value);
        }
    }

    public static AvailabilityScore of(int value) {
        return new AvailabilityScore(value);
    }
}