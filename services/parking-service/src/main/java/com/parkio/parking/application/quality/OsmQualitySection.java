package com.parkio.parking.application.quality;

import java.util.Map;

/** OSM-specific label/coverage quality. Occupancy is always absent for OSM by design. */
public record OsmQualitySection(
        boolean importEnabled,
        boolean schedulerEnabled,
        boolean publicationEnabled,
        String clipVersion,
        String labelPolicyVersion,
        long activeFacilities,
        CoverageMetric nameBearingLabelCoverage,
        long technicalLabelCount,
        long staleNameMismatchCount,
        long occupancySnapshotCount,
        CoverageMetric nullAvailabilityCoverage,
        Map<String, Long> labelOutcomes,
        NormalizedQualityReport latestImportReport) {}
