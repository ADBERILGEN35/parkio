package com.parkio.parking.application.quality;

import java.time.Instant;
import java.util.List;

/**
 * Single-source detail. Exactly one of {@code osm} / {@code izum} is populated,
 * matching the requested source family.
 */
public record SourceQualityDetail(
        String policyVersion,
        Instant generatedAt,
        SourceQualitySummary summary,
        OsmQualitySection osm,
        IzumQualitySection izum,
        int recentRunLimit,
        List<RecentSyncRunSummary> recentRuns) {}
