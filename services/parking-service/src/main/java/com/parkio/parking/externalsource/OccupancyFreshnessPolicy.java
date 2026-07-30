package com.parkio.parking.externalsource;

import java.time.Duration;
import java.time.Instant;

/** Pure freshness classification. Source age, when supplied, is added to transport age. */
public final class OccupancyFreshnessPolicy {
    private final Duration agingAfter;
    private final Duration staleAfter;

    public OccupancyFreshnessPolicy(Duration agingAfter, Duration staleAfter) {
        if (agingAfter.isNegative() || staleAfter.compareTo(agingAfter) < 0) {
            throw new IllegalArgumentException("staleAfter must be >= agingAfter");
        }
        this.agingAfter = agingAfter;
        this.staleAfter = staleAfter;
    }

    public MunicipalOccupancyFreshness classify(
            Long sourceAgeSeconds, Instant fetchedAt, Instant now, boolean valid, boolean available) {
        if (!valid) return MunicipalOccupancyFreshness.INVALID;
        if (!available || fetchedAt == null) return MunicipalOccupancyFreshness.UNAVAILABLE;
        Duration age = Duration.between(fetchedAt, now);
        if (age.isNegative()) age = Duration.ZERO;
        if (sourceAgeSeconds != null && sourceAgeSeconds > 0) {
            age = age.plusSeconds(sourceAgeSeconds);
        }
        if (age.compareTo(staleAfter) >= 0) return MunicipalOccupancyFreshness.STALE;
        if (age.compareTo(agingAfter) >= 0) return MunicipalOccupancyFreshness.AGING;
        return MunicipalOccupancyFreshness.LIVE;
    }
}
