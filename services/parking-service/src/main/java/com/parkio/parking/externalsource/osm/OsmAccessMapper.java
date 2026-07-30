package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import java.util.Locale;

public final class OsmAccessMapper {
    private OsmAccessMapper() {}

    public static MunicipalAccessClassification map(String access) {
        if (access == null || access.isBlank()) {
            return MunicipalAccessClassification.UNKNOWN;
        }
        return switch (access.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "public" -> MunicipalAccessClassification.PUBLIC;
            case "customers", "customers_only" -> MunicipalAccessClassification.CUSTOMERS;
            case "permissive" -> MunicipalAccessClassification.PERMISSIVE;
            case "private" -> MunicipalAccessClassification.PRIVATE;
            case "residents", "private;residents" -> MunicipalAccessClassification.RESIDENTS;
            case "no", "destination", "military", "permit" -> MunicipalAccessClassification.RESTRICTED;
            default -> MunicipalAccessClassification.UNKNOWN;
        };
    }

    /** Public discovery visibility: only PUBLIC and PERMISSIVE are published by default. */
    public static boolean publishable(MunicipalAccessClassification access) {
        return access == MunicipalAccessClassification.PUBLIC
                || access == MunicipalAccessClassification.PERMISSIVE
                || access == MunicipalAccessClassification.UNKNOWN;
    }
}