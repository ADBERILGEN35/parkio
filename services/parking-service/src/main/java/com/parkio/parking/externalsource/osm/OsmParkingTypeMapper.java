package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.Locale;

public final class OsmParkingTypeMapper {
    private OsmParkingTypeMapper() {}

    public static MunicipalFacilityType map(String parking, String building) {
        String p = parking == null ? "" : parking.trim().toLowerCase(Locale.ROOT);
        if (p.contains("street") || p.equals("lane") || p.equals("street_side")) {
            return MunicipalFacilityType.ON_STREET;
        }
        if (p.isEmpty() && building == null) {
            return MunicipalFacilityType.UNKNOWN;
        }
        return MunicipalFacilityType.OFF_STREET;
    }
}