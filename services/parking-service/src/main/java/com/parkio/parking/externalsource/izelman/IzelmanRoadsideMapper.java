package com.parkio.parking.externalsource.izelman;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IzelmanRoadsideMapper {
    public NormalizedRoadsideSegment map(Map<String, String> row) {
        String name = row.get("OTOPARK_ADI");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("roadside name is required");
        Double lat = IzelmanFacilityMapper.coordinate(row.get("ENLEM"), -90, 90);
        Double lng = IzelmanFacilityMapper.coordinate(row.get("BOYLAM"), -180, 180);
        var kind = lat != null && lng != null ? NormalizedRoadsideSegment.GeometryKind.POINT
                : row.get("ADRES_VEYA_TARIF") != null && !row.get("ADRES_VEYA_TARIF").isBlank()
                ? NormalizedRoadsideSegment.GeometryKind.STREET_NAME_ONLY
                : NormalizedRoadsideSegment.GeometryKind.UNKNOWN;
        String externalId = IzelmanExternalId.of(
                IzelmanSourceKeys.ROADSIDE, name, lat, lng, row.get("ILCE"));
        String hours = "{\"opens\":\"" + safe(row.get("ACILIS_SAATI"))
                + "\",\"closes\":\"" + safe(row.get("KAPANIS_SAATI")) + "\"}";
        return new NormalizedRoadsideSegment(
                externalId, name, row.get("ILCE"), row.get("MAHALLE"), row.get("ADRES_VEYA_TARIF"),
                hours, lat, lng, IzelmanFacilityMapper.nonNegativeInteger(row.get("KAPASITE")), kind,
                null, Map.of("availableSpaces", "UNKNOWN",
                        "attribution", "İzmir Metropolitan Municipality / İZELMAN A.Ş."),
                IzelmanCsvReader.sha256(row.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
