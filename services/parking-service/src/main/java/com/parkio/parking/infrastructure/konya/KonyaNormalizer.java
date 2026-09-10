package com.parkio.parking.infrastructure.konya;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KonyaNormalizer {
    static final String OPERATOR_NAME = "Konya Büyükşehir Belediyesi";

    private final ObjectMapper objectMapper;

    public KonyaNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedMunicipalFacility facility(KonyaZoneAggregator.AggregatedZone zone) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceDataset", "otopark-bilgileri");
        metadata.put("aggregationGrain", "bolgeadi");
        metadata.put("sourceRowCount", zone.sourceRowCount());
        metadata.put("validCoordinateCount", zone.validCoordinateCount());
        if (zone.openingHours() != null && !zone.openingHours().isBlank()) {
            metadata.put("workHours", zone.openingHours());
            metadata.put("schedule", zone.openingHours());
        }
        return new NormalizedMunicipalFacility(
                zone.externalId(),
                OPERATOR_NAME,
                MunicipalFacilityType.UNKNOWN,
                zone.displayName(),
                zone.addressText(),
                zone.latitude(),
                zone.longitude(),
                zone.capacityTotal(),
                MunicipalAccessClassification.PUBLIC,
                metadata,
                hash(zone));
    }

    private String hash(KonyaZoneAggregator.AggregatedZone zone) {
        try {
            byte[] json = objectMapper.writeValueAsString(zone).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash Konya zone", ex);
        }
    }
}
