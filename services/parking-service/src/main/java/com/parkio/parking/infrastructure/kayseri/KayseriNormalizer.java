package com.parkio.parking.infrastructure.kayseri;

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
public class KayseriNormalizer {
    static final String OPERATOR_NAME = "Kayseri Buyuksehir Belediyesi";

    private final ObjectMapper objectMapper;

    public KayseriNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedMunicipalFacility facility(KayseriParkingRecordDto record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (record.ilceCbno() != null) {
            metadata.put("ilceCbno", record.ilceCbno());
        }
        if (record.mahCbno() != null) {
            metadata.put("mahCbno", record.mahCbno());
        }
        if (record.kategori() != null) {
            metadata.put("kategori", record.kategori());
        }
        if (record.altkategor() != null) {
            metadata.put("altkategor", record.altkategor());
        }
        if (record.katId() != null) {
            metadata.put("katId", record.katId());
        }
        return new NormalizedMunicipalFacility(
                record.cbno().trim(),
                OPERATOR_NAME,
                MunicipalFacilityType.UNKNOWN,
                KayseriRecordValidator.displayName(record),
                null,
                record.latDd(),
                record.lonDd(),
                null, // source has no trustworthy capacity field
                MunicipalAccessClassification.PUBLIC,
                metadata,
                hash(record));
    }

    private String hash(KayseriParkingRecordDto record) {
        try {
            byte[] json = objectMapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash Kayseri record", ex);
        }
    }
}
