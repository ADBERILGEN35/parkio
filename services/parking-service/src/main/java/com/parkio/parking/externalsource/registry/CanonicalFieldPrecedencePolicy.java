package com.parkio.parking.externalsource.registry;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import java.util.Locale;
import java.util.Set;

/**
 * Single authority for canonical registry field selection.
 * Occupancy is deliberately excluded: only IZUM may provide live availability.
 */
public final class CanonicalFieldPrecedencePolicy {
    private static final Set<String> MUNICIPAL_FAMILIES = Set.of("IZUM", "IZELMAN");

    private CanonicalFieldPrecedencePolicy() {}

    public static boolean maySupplyLiveOccupancy(String sourceKey) {
        return MunicipalSourceIdentity.isIzum(sourceKey);
    }

    public static boolean mayCreateOccupancySnapshot(String sourceKey) {
        return maySupplyLiveOccupancy(sourceKey);
    }

    public static String preferName(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    public static String preferOperator(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    public static String preferAddress(String municipal, String osm) {
        return firstNonBlank(municipal, osm);
    }

    public static Integer preferStaticCapacity(
            Integer currentMunicipal,
            FieldProvenanceSelection.SourceAgeClass municipalAge,
            Integer osm) {
        if (currentMunicipal != null
                && municipalAge != FieldProvenanceSelection.SourceAgeClass.HISTORICAL) {
            return currentMunicipal;
        }
        return osm;
    }

    public static MunicipalAccessClassification preferAccess(
            MunicipalAccessClassification municipal, MunicipalAccessClassification osm) {
        if (isRestrictive(municipal)) {
            return municipal;
        }
        if (isRestrictive(osm)) {
            return osm;
        }
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

    public static boolean mayPublishRegistryField(String sourceKey, boolean sourcePublicationEnabled) {
        return sourcePublicationEnabled && sourceKey != null && !sourceKey.isBlank();
    }

    public static boolean maySelectTariff(
            String sourceKey, FieldProvenanceSelection.SourceAgeClass age, boolean explicitAssignment) {
        return explicitAssignment
                && age == FieldProvenanceSelection.SourceAgeClass.CURRENT
                && "IZELMAN".equals(sourceFamily(sourceKey));
    }

    public static String sourceFamily(String sourceKey) {
        if (sourceKey == null) {
            return "UNKNOWN";
        }
        String normalized = sourceKey.toLowerCase(Locale.ROOT);
        if (normalized.contains("izum")) {
            return "IZUM";
        }
        if (normalized.contains("izelman")) {
            return "IZELMAN";
        }
        if (normalized.startsWith("osm-") || normalized.contains("openstreetmap")) {
            return "OSM";
        }
        return "UNKNOWN";
    }

    public static boolean isVerifiedMunicipal(String sourceKey) {
        return MUNICIPAL_FAMILIES.contains(sourceFamily(sourceKey));
    }

    private static boolean isRestrictive(MunicipalAccessClassification value) {
        return value == MunicipalAccessClassification.PRIVATE
                || value == MunicipalAccessClassification.RESIDENTS
                || value == MunicipalAccessClassification.RESTRICTED;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}