package com.parkio.parking.application.quality;

import java.time.Instant;
import java.util.List;

/** Overall municipal quality/coverage report across all supported sources. */
public record MunicipalQualityReport(
        String policyVersion,
        Instant generatedAt,
        long activeFacilities,
        List<SourceQualitySummary> sources,
        OsmQualitySection osm,
        IzumQualitySection izum,
        IntegrityGuardrails integrity) {}
