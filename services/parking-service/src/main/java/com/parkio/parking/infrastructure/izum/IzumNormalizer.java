package com.parkio.parking.infrastructure.izum;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IzumNormalizer {
    private final ObjectMapper objectMapper;

    public IzumNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedMunicipalFacility facility(IzumParkingRecordDto record) {
        IzumParkingRecordDto.Total total = record.occupancy().total();
        Integer capacity = total.free() != null && total.occupied() != null
                ? total.free() + total.occupied() : null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", record.status());
        metadata.put("isPaid", record.isPaid());
        metadata.put("nonstop", record.nonstop());
        metadata.put("openingHours", record.openingHours());
        return new NormalizedMunicipalFacility(
                record.ufid(), record.provider(), facilityType(record.type()), record.name(), record.address(),
                record.lat(), record.lng(), capacity, metadata, hash(record));
    }

    public NormalizedMunicipalOccupancy occupancy(IzumParkingRecordDto record, Instant fetchedAt) {
        IzumParkingRecordDto.Total total = record.occupancy().total();
        Integer capacity = total.free() != null && total.occupied() != null
                ? total.free() + total.occupied() : null;
        return new NormalizedMunicipalOccupancy(
                record.ufid(), null, fetchedAt, MunicipalTimestampProvenance.FETCH, capacity,
                total.occupied(), total.free(), MunicipalOccupancyFreshness.LIVE, hash(record));
    }

    private static MunicipalFacilityType facilityType(String value) {
        if ("OnStreet".equalsIgnoreCase(value)) return MunicipalFacilityType.ON_STREET;
        if ("OffStreet".equalsIgnoreCase(value)) return MunicipalFacilityType.OFF_STREET;
        return MunicipalFacilityType.UNKNOWN;
    }

    private String hash(IzumParkingRecordDto record) {
        try {
            byte[] json = objectMapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash IZUM record", ex);
        }
    }
}
