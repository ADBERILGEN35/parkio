package com.parkio.parking.externalsource.izelman;

import java.util.Map;

public record NormalizedRoadsideSegment(
        String externalId, String displayName, String district, String neighborhood,
        String addressOrDescription, String openingHoursJson, Double latitude, Double longitude,
        Integer capacityTotal, GeometryKind geometryKind, Boolean paymentRequired,
        Map<String, Object> sourceMetadata, String rawRecordHash) {
    public enum GeometryKind { POINT, STREET_NAME_ONLY, UNKNOWN }
}
