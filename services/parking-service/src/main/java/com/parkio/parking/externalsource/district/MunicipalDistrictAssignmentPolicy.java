package com.parkio.parking.externalsource.district;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic district assignment for active facility points (DATA-WP-18).
 *
 * <p>Uses polygon {@code covers} (interior + boundary). Never infers district from address,
 * name, operator, attribution, OSM tags or source labels.
 */
public final class MunicipalDistrictAssignmentPolicy {
    public enum Classification {
        ASSIGNED,
        UNASSIGNED,
        INVALID_COORDINATES
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
    }

    private final List<MunicipalDistrictGeometry> districts;

    public MunicipalDistrictAssignmentPolicy(List<MunicipalDistrictGeometry> districts) {
        this.districts = List.copyOf(Objects.requireNonNull(districts, "districts"));
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
        matches.sort(Comparator.comparing(MunicipalDistrictGeometry::foldedName));
        boolean overlap = matches.size() > 1;
        return Assignment.assigned(matches.get(0), overlap);
    }
}
