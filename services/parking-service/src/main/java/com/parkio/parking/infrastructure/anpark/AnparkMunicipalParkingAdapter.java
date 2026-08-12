package com.parkio.parking.infrastructure.anpark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.AnparkParkingClient;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Ankara / ANPARK inventory-only municipal adapter (PROVIDER-ANKARA-01).
 *
 * <p>Upstream has no trustworthy live occupancy. {@link #normalizeOccupancy} always returns empty.
 * Explicit {@code active=false} rows are excluded from the authoritative sync set so missing-set
 * reconciliation soft-deactivates them without counting them as validation rejects.
 */
@Component
public class AnparkMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "ankara-anpark-parks";
    private static final Set<String> REQUIRED = Set.of(
            "id", "name", "lat", "lng", "capacity", "type", "district", "address", "schedule", "active");

    private final AnparkParkingClient client;
    private final ObjectMapper objectMapper;
    private final AnparkRecordValidator validator;
    private final AnparkNormalizer normalizer;

    public AnparkMunicipalParkingAdapter(
            AnparkParkingClient client,
            ObjectMapper objectMapper,
            AnparkRecordValidator validator,
            AnparkNormalizer normalizer) {
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
        return ParkingDataProviderId.ANPARK;
    }

    @Override
    public Set<ProviderCapability> capabilities() {
        return Set.of(ProviderCapability.FACILITY_INVENTORY);
    }

    @Override
    public ReconciliationMode reconciliationMode() {
        return ReconciliationMode.AUTHORITATIVE_FULL_SET;
    }

    @Override
    public JsonNode fetch() {
        return excludeInactive(client.fetch());
    }

    @Override
    public SchemaFingerprint validateContract(JsonNode payload) {
        SchemaFingerprint fingerprint = SchemaFingerprint.fromArray(payload);
        if (!fingerprint.fields().containsAll(REQUIRED)) {
            throw new IllegalArgumentException("ANPARK contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt) {
        List<NormalizedMunicipalFacility> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (AnparkParkingRecordDto record : records(payload)) {
            if (record == null || record.id() == null || record.id().isBlank()) {
                continue;
            }
            if (Boolean.FALSE.equals(record.active())) {
                continue; // defense in depth; fetch() already excludes inactive
            }
            String externalId = record.id().trim();
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
        // Inventory-only: never invent occupancy from capacity.
        return List.of();
    }

    /**
     * Drop explicit {@code active=false} rows from the sync payload so they are treated as absent
     * for authoritative missing-set soft-deactivation, without inflating reject counts.
     */
    JsonNode excludeInactive(JsonNode payload) {
        if (payload == null || !payload.isArray()) {
            return payload;
        }
        ArrayNode filtered = objectMapper.createArrayNode();
        for (JsonNode node : payload) {
            if (node != null && node.isObject()) {
                JsonNode active = node.get("active");
                if (active != null && active.isBoolean() && !active.booleanValue()) {
                    continue;
                }
            }
            filtered.add(node);
        }
        return filtered;
    }

    private List<AnparkParkingRecordDto> records(JsonNode payload) {
        try {
            return objectMapper.readerForListOf(AnparkParkingRecordDto.class).readValue(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid ANPARK payload", ex);
        }
    }
}
