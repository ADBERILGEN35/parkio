package com.parkio.parking.infrastructure.ispark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.IsparkParkingClient;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IsparkMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "istanbul-ispark-parks";
    private static final Set<String> REQUIRED = Set.of(
            "parkID", "parkName", "lat", "lng", "capacity", "emptyCapacity");

    private final IsparkParkingClient client;
    private final ObjectMapper objectMapper;
    private final IsparkRecordValidator validator;
    private final IsparkNormalizer normalizer;

    public IsparkMunicipalParkingAdapter(
            IsparkParkingClient client,
            ObjectMapper objectMapper,
            IsparkRecordValidator validator,
            IsparkNormalizer normalizer) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.normalizer = normalizer;
    }

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public ParkingDataProviderId providerId() {
        return ParkingDataProviderId.ISPARK;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY);
    }

    @Override
    public ReconciliationMode reconciliationMode() {
        return ReconciliationMode.AUTHORITATIVE_FULL_SET;
    }

    @Override
    public JsonNode fetch() {
        return client.fetch();
    }

    @Override
    public SchemaFingerprint validateContract(JsonNode payload) {
        SchemaFingerprint fingerprint = SchemaFingerprint.fromArray(payload);
        if (!fingerprint.fields().containsAll(REQUIRED)) {
            throw new IllegalArgumentException("ISPARK contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalFacility> result = new ArrayList<>();
        Set<Integer> seenParkIds = new HashSet<>();
        for (IsparkParkingRecordDto record : records(payload)) {
            if (record == null || record.parkID() == null) {
                continue;
            }
            if (!seenParkIds.add(record.parkID())) {
                continue; // duplicate parkID → reject (counted via received - accepted)
            }
            if (validator.validate(record).valid()) {
                result.add(normalizer.facility(record));
            }
        }
        return result;
    }

    @Override
    public List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalOccupancy> result = new ArrayList<>();
        Set<Integer> seenParkIds = new HashSet<>();
        for (IsparkParkingRecordDto record : records(payload)) {
            if (record == null || record.parkID() == null) {
                continue;
            }
            if (!seenParkIds.add(record.parkID())) {
                continue;
            }
            if (validator.validate(record).valid()) {
                result.add(normalizer.occupancy(record, fetchedAt));
            }
        }
        return result;
    }

    private List<IsparkParkingRecordDto> records(JsonNode payload) {
        try {
            return objectMapper.readerForListOf(IsparkParkingRecordDto.class).readValue(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid ISPARK payload", ex);
        }
    }
}
