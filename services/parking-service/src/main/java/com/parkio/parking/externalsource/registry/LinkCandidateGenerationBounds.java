package com.parkio.parking.externalsource.registry;

public record LinkCandidateGenerationBounds(
        double maxDistanceMeters, int leftRecordLimit, int pairLimit, int sampleLimit) {
    public static final double DEFAULT_DISTANCE = 100.0;
    public static final double MAX_DISTANCE = 250.0;
    public static final int DEFAULT_LEFT = 100;
    public static final int MAX_LEFT = 1000;
    public static final int DEFAULT_PAIR = 1000;
    public static final int MAX_PAIR = 10_000;
    public static final int DEFAULT_SAMPLE = 20;
    public static final int MAX_SAMPLE = 20;

    public static LinkCandidateGenerationBounds normalize(
            Double distance, Integer left, Integer pairs, Integer samples) {
        double normalizedDistance = positive(distance, DEFAULT_DISTANCE, "maxDistanceMeters");
        int normalizedLeft = positive(left, DEFAULT_LEFT, "leftRecordLimit");
        int normalizedPairs = positive(pairs, DEFAULT_PAIR, "pairLimit");
        int normalizedSamples = positive(samples, DEFAULT_SAMPLE, "sampleLimit");
        return new LinkCandidateGenerationBounds(
                Math.min(normalizedDistance, MAX_DISTANCE),
                Math.min(normalizedLeft, MAX_LEFT),
                Math.min(normalizedPairs, MAX_PAIR),
                Math.min(normalizedSamples, MAX_SAMPLE));
    }

    private static double positive(Double value, double fallback, String field) {
        if (value == null) return fallback;
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int positive(Integer value, int fallback, String field) {
        if (value == null) return fallback;
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
