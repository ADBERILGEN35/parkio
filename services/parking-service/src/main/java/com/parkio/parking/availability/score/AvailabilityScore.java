package com.parkio.parking.availability.score;

/**
 * Integer availability score for a parking opportunity, scale {@code 0..100}.
 *
 * <p>Belongs to the availability domain ({@code com.parkio.parking.availability}),
 * not the Decision Engine {@code decision.score.AvailabilityScore} placeholder.
 *
 * <p>Zero means no remaining confidence; it is a valid computed outcome.
 * Unknown availability is represented by {@link com.parkio.parking.availability.AvailabilityState#UNKNOWN},
 * not by forcing score to zero.
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
