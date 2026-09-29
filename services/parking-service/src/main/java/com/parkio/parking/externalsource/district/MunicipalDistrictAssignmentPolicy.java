package com.parkio.parking.externalsource.district;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic district assignment for active facility points.
 *
 * <p>Legacy (DATA-WP-18): {@code covers} + folded-name tie-break with overlap flag.
 *
 * <p>Topology (DATA-WP-19): JTS covers; material multi-interior → {@code TOPOLOGY_AMBIGUOUS}
 * (no name-order assignment); boundary-only multi → {@code BOUNDARY_AMBIGUOUS} (excluded from
 * district totals).
 */
public final class MunicipalDistrictAssignmentPolicy {
    public enum Classification {
        ASSIGNED,
        UNASSIGNED,
        INVALID_COORDINATES,
        BOUNDARY_AMBIGUOUS,
        TOPOLOGY_AMBIGUOUS
    }

    public record Assignment(
            Classification classification,
            String districtName,
            String foldedName,
            boolean overlapAnomaly) {
        public static Assignment invalid() {
            return new Assignment(Classification.INVALID_COORDINATES, null, null, false);
        }

        public static Assignment unassigned() {
            return new Assignment(Classification.UNASSIGNED, null, null, false);
        }

        public static Assignment assigned(MunicipalDistrictGeometry district, boolean overlap) {
            return new Assignment(
                    Classification.ASSIGNED,
                    district.districtName(),
                    district.foldedName(),
                    overlap);
        }

        public static Assignment boundaryAmbiguous() {
            return new Assignment(Classification.BOUNDARY_AMBIGUOUS, null, null, false);
        }

        public static Assignment topologyAmbiguous() {
            return new Assignment(Classification.TOPOLOGY_AMBIGUOUS, null, null, true);
        }
    }

    private final List<MunicipalDistrictGeometry> districts;
    private final boolean topologyMode;

    public MunicipalDistrictAssignmentPolicy(List<MunicipalDistrictGeometry> districts) {
        this(districts, districts != null && !districts.isEmpty() && districts.get(0).topologyMode());
    }

    public MunicipalDistrictAssignmentPolicy(
            List<MunicipalDistrictGeometry> districts, boolean topologyMode) {
        this.districts = List.copyOf(Objects.requireNonNull(districts, "districts"));
        this.topologyMode = topologyMode;
    }

    public Assignment assign(Double longitude, Double latitude) {
        if (longitude == null
                || latitude == null
                || !Double.isFinite(longitude)
                || !Double.isFinite(latitude)) {
            return Assignment.invalid();
        }
        List<MunicipalDistrictGeometry> matches = new ArrayList<>();
        for (MunicipalDistrictGeometry district : districts) {
            if (district.covers(longitude, latitude)) {
                matches.add(district);
            }
        }
        if (matches.isEmpty()) {
            return Assignment.unassigned();
        }
        if (!topologyMode) {
            matches.sort(java.util.Comparator.comparing(MunicipalDistrictGeometry::foldedName));
            boolean overlap = matches.size() > 1;
            return Assignment.assigned(matches.get(0), overlap);
        }
        if (matches.size() == 1) {
            return Assignment.assigned(matches.get(0), false);
        }
        boolean allBoundary = true;
        for (MunicipalDistrictGeometry m : matches) {
            if (!m.onBoundaryOnly(longitude, latitude)) {
                allBoundary = false;
                break;
            }
        }
        if (allBoundary) {
            return Assignment.boundaryAmbiguous();
        }
        return Assignment.topologyAmbiguous();
    }
}
