package com.parkio.parking.infrastructure.ispark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IsparkNormalizer {
    static final String OPERATOR_NAME = "İSPARK";

    private final ObjectMapper objectMapper;

    public IsparkNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedMunicipalFacility facility(IsparkParkingRecordDto record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (record.isOpen() != null) {
            metadata.put("isOpen", record.isOpen());
        }
        if (record.workHours() != null && !record.workHours().isBlank()) {
            metadata.put("workHours", record.workHours().trim());
        }
        if (record.parkType() != null && !record.parkType().isBlank()) {
            metadata.put("parkType", record.parkType().trim());
        }
        if (record.district() != null && !record.district().isBlank()) {
            metadata.put("district", record.district().trim());
        }
        if (record.freeTime() != null) {
            metadata.put("freeTimeMinutes", record.freeTime());
        }
        String address = addressText(record);
        return new NormalizedMunicipalFacility(
                String.valueOf(record.parkID()),
                OPERATOR_NAME,
                facilityType(record.parkType()),
                record.parkName().trim(),
                address,
                record.lat(),
                record.lng(),
                record.capacity(),
                MunicipalAccessClassification.PUBLIC,
                metadata,
                hash(record));
    }

    public NormalizedMunicipalOccupancy occupancy(IsparkParkingRecordDto record, Instant fetchedAt) {
        int capacity = record.capacity();
        int available = record.emptyCapacity();
        int occupied = capacity - available;
        return new NormalizedMunicipalOccupancy(
                String.valueOf(record.parkID()),
                null,
                fetchedAt,
                MunicipalTimestampProvenance.FETCH,
                capacity,
                occupied,
                available,
                MunicipalOccupancyFreshness.LIVE,
                hash(record));
    }

    static MunicipalFacilityType facilityType(String parkType) {
        if (parkType == null || parkType.isBlank()) {
            return MunicipalFacilityType.UNKNOWN;
        }
        String folded = fold(parkType);
        if (folded.contains("YOL") && (folded.contains("UST") || folded.contains("USTU"))) {
            return MunicipalFacilityType.ON_STREET;
        }
        if (folded.contains("ACIK") || folded.contains("KAPALI")) {
            return MunicipalFacilityType.OFF_STREET;
        }
        return MunicipalFacilityType.UNKNOWN;
    }

    private static String addressText(IsparkParkingRecordDto record) {
        if (record.district() == null || record.district().isBlank()) {
            return null;
        }
        return record.district().trim();
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
    }

    private String hash(IsparkParkingRecordDto record) {
        try {
            byte[] json = objectMapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash ISPARK record", ex);
        }
    }
}
