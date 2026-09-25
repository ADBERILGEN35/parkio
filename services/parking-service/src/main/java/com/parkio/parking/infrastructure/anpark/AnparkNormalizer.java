package com.parkio.parking.infrastructure.anpark;

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
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnparkNormalizer {
    /** Operator display for canonical facility rows (BELTAŞ brand ANPARK). */
    static final String OPERATOR_NAME = "ANPARK";

    private final ObjectMapper objectMapper;

    public AnparkNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedMunicipalFacility facility(AnparkParkingRecordDto record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (record.type() != null && !record.type().isBlank()) {
            metadata.put("anparkType", record.type().trim());
        }
        if (record.district() != null && !record.district().isBlank()) {
            metadata.put("district", record.district().trim());
        }
        if (record.schedule() != null && !record.schedule().isBlank()) {
            metadata.put("schedule", record.schedule().trim());
            metadata.put("workHours", record.schedule().trim());
        }
        if (record.active() != null) {
            metadata.put("upstreamActive", record.active());
        }
        Integer capacity = normalizeCapacity(record.capacity());
        return new NormalizedMunicipalFacility(
                record.id().trim(),
                OPERATOR_NAME,
                facilityType(record.type()),
                record.name().trim(),
                addressText(record),
                record.lat(),
                record.lng(),
                capacity,
                MunicipalAccessClassification.PUBLIC,
                metadata,
                hash(record));
    }

    /**
     * Upstream recreation sites report {@code capacity=0}. That is not evidence of a known
     * zero-space lot and must never become availableSpaces/occupiedSpaces. Map non-positive
     * capacity to canonical unknown ({@code null}), matching OSM/static inventory precedent.
     */
    static Integer normalizeCapacity(Integer capacity) {
        if (capacity == null || capacity <= 0) {
            return null;
        }
        return capacity;
    }

    /**
     * Type mapping (documented):
     * <ul>
     *   <li>{@code yolustu} → {@link MunicipalFacilityType#ON_STREET}</li>
     *   <li>{@code acik} → {@link MunicipalFacilityType#OFF_STREET}</li>
     *   <li>{@code kapali} → {@link MunicipalFacilityType#OFF_STREET}</li>
     *   <li>{@code rekreasyon} → {@link MunicipalFacilityType#OFF_STREET}</li>
     *   <li>unknown / blank → {@link MunicipalFacilityType#UNKNOWN}</li>
     * </ul>
     */
    static MunicipalFacilityType facilityType(String type) {
        if (type == null || type.isBlank()) {
            return MunicipalFacilityType.UNKNOWN;
        }
        String key = type.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "yolustu" -> MunicipalFacilityType.ON_STREET;
            case "acik", "kapali", "rekreasyon" -> MunicipalFacilityType.OFF_STREET;
            default -> MunicipalFacilityType.UNKNOWN;
        };
    }

    private static String addressText(AnparkParkingRecordDto record) {
        if (record.address() != null && !record.address().isBlank()) {
            return record.address().trim();
        }
        if (record.district() != null && !record.district().isBlank()) {
            return record.district().trim();
        }
        return null;
    }

    private String hash(AnparkParkingRecordDto record) {
        try {
            byte[] json = objectMapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash ANPARK record", ex);
        }
    }
}
