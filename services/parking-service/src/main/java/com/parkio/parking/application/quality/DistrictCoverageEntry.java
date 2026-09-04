package com.parkio.parking.application.quality;

/**
 * Per-district bounded aggregates. Empty counts mean zero currently active imported facilities
 * assigned to that geometry — not absence of parking, demand shortage, or source failure.
 */
public record DistrictCoverageEntry(
        String districtName,
        long totalActiveFacilities,
        long activeOsmFacilities,
        long activeIzumFacilities,
        long availabilityExposedIzumFacilities,
        long realNameOsmFacilities,
        long neutralFallbackOsmFacilities,
        long provenanceCoveredFacilities) {}
