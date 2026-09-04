package com.parkio.parking.application.quality;

import java.time.Instant;
import java.util.List;

/**
 * Per-source coverage and SLA summary. Carries no aggregate quality score,
 * trust score, linking readiness or production-readiness verdict.
 */
public record SourceQualitySummary(
        String sourceKey,
        String sourceFamily,
        String sourceMode,
        boolean municipalEnabled,
        boolean sourceEnabled,
        boolean schedulerEnabled,
        boolean publicationEnabled,
        String operationalState,
        String lastRunStatus,
        Instant lastRunAt,
        Instant lastSuccessAt,
        long secondsSinceSuccess,
        int consecutiveFailures,
        int failuresInWindow,
        int staleRunningOperations,
        String lastFailureCategory,
        String occupancyFreshness,
        long activeFacilities,
        long activeSourceLinks,
        CoverageMetric shareOfActiveFacilities,
        List<ProvenanceFieldCoverage> provenanceCoverage) {}
