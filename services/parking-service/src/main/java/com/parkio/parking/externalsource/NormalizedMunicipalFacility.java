package com.parkio.parking.externalsource;

import java.util.Map;

public record NormalizedMunicipalFacility(
        String externalId,
        String operatorName,
        MunicipalFacilityType facilityType,
        String displayName,
        String addressText,
        double latitude,
        double longitude,
        Integer capacityTotal,
        Map<String, Object> sourceMetadata,
        String rawRecordHash) {}
