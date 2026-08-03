package com.parkio.parking.application.quality;

import java.time.Instant;
import java.util.List;

/**
 * Bounded İzmir district coverage section on the WP-15 overall quality report (DATA-WP-18).
 *
 * <p>Never exposes polygons, GeoJSON, coordinates, facility IDs, paths or raw validation errors.
 */
public record DistrictCoverageSection(
        DistrictCoverageStatus status,
        String unavailableReason,
        String policyVersion,
        String assetVersion,
        Instant generatedAt,
        int districtCount,
        long activeFacilityCountConsidered,
        long assignedFacilityCount,
        long unassignedFacilityCount,
        long invalidCoordinateCount,
        long overlapAnomalyCount,
        List<DistrictCoverageEntry> districts) {

    public static DistrictCoverageSection disabled(Instant generatedAt) {
        return new DistrictCoverageSection(
                DistrictCoverageStatus.DISABLED,
                MunicipalDistrictCoverageReason.DISABLED,
                null,
                null,
                generatedAt,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of());
    }

    public static DistrictCoverageSection unavailable(Instant generatedAt, String reason) {
        return new DistrictCoverageSection(
                DistrictCoverageStatus.UNAVAILABLE,
                reason,
                com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy.POLICY_VERSION,
                com.parkio.parking.externalsource.district.MunicipalDistrictCoveragePolicy.ASSET_VERSION,
                generatedAt,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of());
    }
}
