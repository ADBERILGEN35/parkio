package com.parkio.parking.application.port;

import java.util.UUID;

/**
 * Bounded active-facility projection for in-memory district assignment (DATA-WP-18).
 * Coordinates and IDs stay inside the assembler; they never appear in the API response.
 */
public record MunicipalDistrictFacilityProjection(
        UUID facilityId,
        Double latitude,
        Double longitude,
        boolean osmLinked,
        boolean izumLinked,
        boolean izumAvailabilityExposed,
        boolean osmRealNameLabel,
        boolean osmNeutralFallbackLabel,
        boolean provenanceCovered) {}
