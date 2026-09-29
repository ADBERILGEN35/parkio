package com.parkio.parking.application.quality;

/** Per-field provenance coverage for one source, bounded to the allow-listed fields. */
public record ProvenanceFieldCoverage(String fieldName, CoverageMetric coverage, long missing) {
    public static ProvenanceFieldCoverage of(String fieldName, long covered, long activeFacilities) {
        return new ProvenanceFieldCoverage(
                fieldName,
                CoverageMetric.of(covered, activeFacilities),
                Math.max(0, activeFacilities - covered));
    }
}
