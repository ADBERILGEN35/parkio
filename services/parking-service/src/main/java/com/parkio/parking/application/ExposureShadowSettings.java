package com.parkio.parking.application;

/** Request-path exposure shadow settings (WP-05.13). */
public record ExposureShadowSettings(
        boolean enabled,
        int samplePercent,
        long timeBudgetMillis) {

    public ExposureShadowSettings {
        if (samplePercent < 0 || samplePercent > 100) {
            throw new IllegalArgumentException("samplePercent must be between 0 and 100");
        }
        if (timeBudgetMillis <= 0) {
            throw new IllegalArgumentException("timeBudgetMillis must be positive");
        }
    }
}
