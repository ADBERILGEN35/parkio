package com.parkio.parking.decision.score;

/**
 * Derived risk intensity for one submission, scale {@code 0..100} (integer).
 *
 * <p>Meaning: how risky would it be to expose this ParkingSpot under the evaluated
 * policy and evidence snapshot? It is <strong>not</strong> AI error probability,
 * user malice probability, occupancy confidence, availability, or completeness alone.
 *
 * <p>{@code 0} = minimal modeled exposure risk given available assessments;
 * {@code 100} = maximum modeled exposure risk. Unknown risk MUST be
 * {@code Optional.empty()}, never {@code 0}. Hard constraints may coexist with any score.
 *
 * <p>Contains no scoring algorithm.
 */
public record RiskScore(int value) {

    public static final int MIN = 0;
    public static final int MAX = 100;

    public RiskScore {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "RiskScore must be between " + MIN + " and " + MAX + " inclusive, got " + value);
        }
    }

    public static RiskScore of(int value) {
        return new RiskScore(value);
    }
}