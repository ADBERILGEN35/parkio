package com.parkio.parking.infrastructure.konya;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.client.KonyaParkingClient;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Konya Büyükşehir Belediyesi / Otopark Bilgileri inventory-only adapter (PROVIDER-KONYA-01).
 *
 * <p>Bay/peron rows are aggregated to zone-level facilities. Invalid/out-of-city coordinates are
 * excluded from centroid input. No live occupancy is published.
 */
@Component
public class KonyaMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "konya-bb-otopark-bilgileri";

    private final KonyaParkingClient client;
    private final ObjectMapper objectMapper;
    private final KonyaZoneAggregator zoneAggregator;
    private final KonyaNormalizer normalizer;

    public KonyaMunicipalParkingAdapter(
            KonyaParkingClient client,
            ObjectMapper objectMapper,
            KonyaZoneAggregator zoneAggregator,
            KonyaNormalizer normalizer) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.zoneAggregator = zoneAggregator;
        this.normalizer = normalizer;
    }

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public ParkingDataProviderId providerId() {
        return ParkingDataProviderId.KONYA;
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
        if (!fingerprint.fields().containsAll(KonyaParkingClient.REQUIRED_FIELDS)) {
            throw new IllegalArgumentException("Konya contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt) {
        KonyaZoneAggregator.AggregationResult aggregation = zoneAggregator.aggregate(records(payload));
        List<NormalizedMunicipalFacility> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (KonyaZoneAggregator.AggregatedZone zone : aggregation.zones()) {
            if (!seenIds.add(zone.externalId())) {
                continue;
            }
            result.add(normalizer.facility(zone));
        }
        return result;
    }

    @Override
    public List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt) {
        return List.of();
    }

    private List<KonyaParkingRecordDto> records(JsonNode payload) {
        try {
            return objectMapper.readerForListOf(KonyaParkingRecordDto.class).readValue(payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Konya payload", ex);
        }
    }
}
