package com.parkio.parking.externalsource.izelman;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IzelmanFacilityMapper {
    public NormalizedMunicipalFacility map(String sourceKey, Map<String, String> row) {
        String name = first(row, "OTOPARK_ADI", "BLOK_ADI");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("parking name is required");
        Double lat = coordinate(row.get("ENLEM"), -90, 90);
        Double lng = coordinate(row.get("BOYLAM"), -180, 180);
        if (lat == null || lng == null) {
            throw new IllegalArgumentException("valid coordinates are required for off-street facilities");
        }
        String district = row.get("ILCE");
        String address = join(row.get("ADRES"), row.get("ADRES_VEYA_TARIF"), row.get("YER_TARIFI"), row.get("EK_BILGI"));
        Integer capacity = nonNegativeInteger(row.get("KAPASITE"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "district", district);
        putIfPresent(metadata, "neighborhood", row.get("MAHALLE"));
        putIfPresent(metadata, "openingTime", row.get("ACILIS_SAATI"));
        putIfPresent(metadata, "closingTime", row.get("KAPANIS_SAATI"));
        metadata.put("availability", "UNKNOWN");
        metadata.put("attribution", "İzmir Metropolitan Municipality / İZELMAN A.Ş.");
        String externalId = IzelmanExternalId.of(sourceKey, name, lat, lng, district);
        String rawHash = IzelmanCsvReader.sha256(row.toString().getBytes(StandardCharsets.UTF_8));
        MunicipalAccessClassification access = IzelmanSourceKeys.BARRIER.equals(sourceKey)
                ? MunicipalAccessClassification.RESTRICTED : MunicipalAccessClassification.PUBLIC;
        return new NormalizedMunicipalFacility(externalId, "İZELMAN A.Ş.", MunicipalFacilityType.OFF_STREET,
                name, address, lat, lng, capacity, access, Map.copyOf(metadata), rawHash);
    }

    static Double coordinate(String value, double min, double max) {
        if (value == null || value.isBlank()) return null;
        try {
            double parsed = Double.parseDouble(value.trim().replace(',', '.'));
            return Double.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Integer nonNegativeInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String first(Map<String, String> row, String... keys) {
        for (String key : keys) if (row.get(key) != null && !row.get(key).isBlank()) return row.get(key);
        return null;
    }

    private static String join(String... values) {
        return java.util.Arrays.stream(values).filter(v -> v != null && !v.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(" — "));
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
