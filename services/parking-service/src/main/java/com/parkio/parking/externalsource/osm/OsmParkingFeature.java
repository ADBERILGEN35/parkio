package com.parkio.parking.externalsource.osm;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.Map;

public record OsmParkingFeature(
        String externalId,
        OsmElementType elementType,
        long osmId,
        String name,
        String operator,
        String brand,
        MunicipalFacilityType facilityType,
        MunicipalAccessClassification access,
        Integer capacity,
        Boolean fee,
        String openingHours,
        double latitude,
        double longitude,
        String geometryType,
        Map<String, String> allowlistedTags,
        String rawRecordHash,
        boolean valid,
        String rejectReason) {}