package com.parkio.parking.application.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numerator/denominator pair with a derived percentage.
 * A zero denominator yields {@code null} percentage rather than a misleading 0 or 100.
 */
public record CoverageMetric(long numerator, long denominator, Double percentage) {
    public static CoverageMetric of(long numerator, long denominator) {
        if (denominator <= 0) {
            return new CoverageMetric(numerator, Math.max(0, denominator), null);
        }
        double pct = BigDecimal.valueOf(numerator * 100.0d / denominator)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
        return new CoverageMetric(numerator, denominator, pct);
    }
}
