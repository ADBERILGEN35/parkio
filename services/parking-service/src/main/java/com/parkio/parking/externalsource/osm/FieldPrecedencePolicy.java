package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;

/**
 * Canonical field ownership when a facility has municipal + OSM source links.
 * Controllers must not reimplement this.
 */
public final class FieldPrecedencePolicy {
    private FieldPrecedencePolicy() {}

    public record Projection(
            String displayName,
            String operatorName,
            MunicipalFacilityType facilityType,
            String addressText,
            Integer capacityTotal,
            MunicipalAccessClassification access,
            String openingHours,
            double latitude,
            double longitude) {}

    public static String preferName(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    public static String preferOperator(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    public static Integer preferCapacity(Integer municipal, Integer osm) {
        return municipal != null ? municipal : osm;
    }

    public static MunicipalAccessClassification preferAccess(
            MunicipalAccessClassification municipal, MunicipalAccessClassification osm) {
        if (municipal != null && municipal != MunicipalAccessClassification.UNKNOWN) {
            return municipal;
        }
        return osm == null ? MunicipalAccessClassification.UNKNOWN : osm;
    }

    public static MunicipalFacilityType preferType(MunicipalFacilityType municipal, MunicipalFacilityType osm) {
        if (municipal != null && municipal != MunicipalFacilityType.UNKNOWN) {
            return municipal;
        }
        return osm == null ? MunicipalFacilityType.UNKNOWN : osm;
    }

    public static String preferOpeningHours(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    /** Live occupancy is never taken from OSM — handled outside this policy. */
    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}