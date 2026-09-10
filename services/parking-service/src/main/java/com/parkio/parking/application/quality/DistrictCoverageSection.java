package com.parkio.parking.application.quality;

import java.time.Instant;
import java.util.List;

/**
 * Bounded İzmir district coverage section on the WP-15 overall quality report
 * (DATA-WP-18 / DATA-WP-19).
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
        List<DistrictCoverageEntry> districts,
        String topologyPolicyVersion,
        String normalizedAssetVersion,
        String topologyStatus,
        long boundaryAmbiguousCount,
        long topologyAmbiguousCount) {

    public DistrictCoverageSection {
        districts = districts == null ? List.of() : List.copyOf(districts);
    }

    /** WP-18 compatibility constructor (topology fields defaulted). */
    public DistrictCoverageSection(
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
        this(
                status,
                unavailableReason,
                policyVersion,
                assetVersion,
                generatedAt,
                districtCount,
                activeFacilityCountConsidered,
                assignedFacilityCount,
                unassignedFacilityCount,
                invalidCoordinateCount,
                overlapAnomalyCount,
                districts,
                null,
                null,
                null,
                0,
                0);
    }

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
                List.of(),
                null,
                null,
                "DISABLED",
                0,
                0);
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
                List.of(),
                null,
                null,
                "UNAVAILABLE",
                0,
                0);
    }
}
