package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.registry.CanonicalFieldPrecedencePolicy;

/**
 * Canonical field ownership across IZUM, IZELMAN, and OSM source links.
 * Controllers must not reimplement this.
 *
 * <p>Live occupancy: IZUM only. IZELMAN static inventory and OSM never supply
 * live availability.
 *
 * <p>Name / operator: verified municipal (IZUM or IZELMAN) preferred over OSM.
 * Do not erase municipal provenance when projecting.
 *
 * <p>Capacity: current verified municipal preferred; aged IZELMAN capacity must
 * retain source-age metadata. Conflicting values keep independent source links.
 *
 * <p>Access: restrictive interpretation wins when evidence conflicts.
 *
 * <p>Tariff: only an assigned tariff plan; never infer from a nearby facility.
 * Historical or unknown-validity tariffs are never treated as CURRENT.
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
        return CanonicalFieldPrecedencePolicy.preferName(municipal, osm);
    }

    public static String preferOperator(String municipal, String osm) {
        return CanonicalFieldPrecedencePolicy.preferOperator(municipal, osm);
    }

    public static Integer preferCapacity(Integer municipal, Integer osm) {
        return municipal != null ? municipal : osm;
    }

    public static MunicipalAccessClassification preferAccess(
            MunicipalAccessClassification municipal, MunicipalAccessClassification osm) {
        return CanonicalFieldPrecedencePolicy.preferAccess(municipal, osm);
    }

    public static MunicipalFacilityType preferType(MunicipalFacilityType municipal, MunicipalFacilityType osm) {
        return CanonicalFieldPrecedencePolicy.preferType(municipal, osm);
    }

    public static String preferOpeningHours(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    /** Live occupancy is never taken from OSM or IZELMAN - handled outside this policy. */
    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}