package com.parkio.parking.infrastructure.kayseri;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.KayseriParkingClient;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Kayseri Büyükşehir Belediyesi / Otoparklar inventory-only adapter (PROVIDER-KAYSERI-01).
 *
 * <p>One GeoJSON feature → one canonical facility keyed by stable {@code CBNO}. No live occupancy.
 * Reconciliation is {@link ReconciliationMode#UPSERT_ONLY}: small GIS layer completeness is not
 * guaranteed as a full municipal inventory.
 */
@Component
public class KayseriMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "kayseri-bb-otoparklar";

    private final KayseriParkingClient client;
    private final ObjectMapper objectMapper;
    private final KayseriRecordValidator validator;
    private final KayseriNormalizer normalizer;

    public KayseriMunicipalParkingAdapter(
            KayseriParkingClient client,
            ObjectMapper objectMapper,
            KayseriRecordValidator validator,
            KayseriNormalizer normalizer) {
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
        return ParkingDataProviderId.KAYSERI;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(ProviderCapability.FACILITY_INVENTORY);
    }

    @Override
    public ReconciliationMode reconciliationMode() {
        return ReconciliationMode.UPSERT_ONLY;
    }

    @Override
    public JsonNode fetch() {
        return client.fetch();
    }

    @Override
    public SchemaFingerprint validateContract(JsonNode payload) {
        SchemaFingerprint fingerprint = SchemaFingerprint.fromArray(payload);
        if (!fingerprint.fields().containsAll(KayseriParkingClient.REQUIRED_FIELDS)) {
            throw new IllegalArgumentException("Kayseri contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalFacility> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (KayseriParkingRecordDto record : records(payload)) {
            if (record == null || record.cbno() == null || record.cbno().isBlank()) {
                continue;
            }
            String externalId = record.cbno().trim();
            if (!seenIds.add(externalId)) {
                continue;
            }
            if (validator.validate(record).valid()) {
                result.add(normalizer.facility(record));
            }
        }
        return result;
    }

    @Override
    public List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt) {
        // Inventory-only: never invent occupancy from capacity or names.
        return List.of();
    }

    private List<KayseriParkingRecordDto> records(JsonNode payload) {
        try {
            return objectMapper.readerForListOf(KayseriParkingRecordDto.class).readValue(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Kayseri payload", ex);
        }
    }
}
