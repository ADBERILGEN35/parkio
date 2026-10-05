package com.parkio.parking.infrastructure.izum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.IzumParkingClient;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IzumMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "izmir-izum-otoparklar";
    private static final Set<String> REQUIRED = Set.of("ufid", "lat", "lng", "occupancy");

    private final IzumParkingClient client;
    private final ObjectMapper objectMapper;
    private final IzumRecordValidator validator;
    private final IzumNormalizer normalizer;
    private final IzumStatusObserver statusObserver;

    public IzumMunicipalParkingAdapter(
            IzumParkingClient client, ObjectMapper objectMapper,
            IzumRecordValidator validator, IzumNormalizer normalizer, IzumStatusObserver statusObserver) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.normalizer = normalizer;
        this.statusObserver = statusObserver;
    }

    @Override public String sourceKey() { return SOURCE_KEY; }

    @Override
    public ParkingDataProviderId providerId() {
        return ParkingDataProviderId.IZUM;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY);
    }

    @Override
    public ReconciliationMode reconciliationMode() {
        return ReconciliationMode.AUTHORITATIVE_FULL_SET;
    }

    @Override public JsonNode fetch() { return client.fetch(); }

    @Override
    public SchemaFingerprint validateContract(JsonNode payload) {
        SchemaFingerprint fingerprint = SchemaFingerprint.fromArray(payload);
        if (!fingerprint.fields().containsAll(REQUIRED)) {
            throw new IllegalArgumentException("IZUM contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalFacility> result = new ArrayList<>();
        for (IzumParkingRecordDto record : records(payload)) {
            if (validator.validate(record).valid()) result.add(normalizer.facility(record));
        }
        return result;
    }

    /** Normalizes one sync run's occupancy, and reports statuses outside the known vocabulary. */
    @Override
    public List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalOccupancy> result = new ArrayList<>();
        List<IzumParkingRecordDto> valid = new ArrayList<>();
        for (IzumParkingRecordDto record : records(payload)) {
            if (validator.validate(record).valid()) {
                valid.add(record);
                result.add(normalizer.occupancy(record, fetchedAt));
            }
        }
        statusObserver.observe(valid);
        return result;
    }

    private List<IzumParkingRecordDto> records(JsonNode payload) {
        try {
            return objectMapper.readerForListOf(IzumParkingRecordDto.class).readValue(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid IZUM payload", ex);
        }
    }
}
