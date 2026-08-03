package com.parkio.parking.application.quality;

/**
 * İZUM occupancy freshness distribution, classified from the latest snapshot per
 * active facility against the source's own aging/stale thresholds.
 */
public record IzumQualitySection(
        boolean enabled,
        boolean schedulerEnabled,
        long agingAfterSeconds,
        long staleAfterSeconds,
        long activeFacilities,
        long facilitiesWithOccupancy,
        CoverageMetric liveCoverage,
        CoverageMetric agingCoverage,
        CoverageMetric staleCoverage,
        CoverageMetric availabilityExposedCoverage) {}
