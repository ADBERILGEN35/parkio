package com.parkio.parking.infrastructure.fake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.externalsource.MunicipalTimestampProvenance;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only live adapter proving multi-provider extensibility.
 * Never production-enabled; never calls external networks.
 */
public class FakeTestMunicipalParkingAdapter implements MunicipalParkingSourceAdapter {
    public static final String SOURCE_KEY = "parkio-fake-test-provider";
    private static final Set<String> REQUIRED = Set.of("externalId", "name", "lat", "lng");

    private final ObjectMapper objectMapper;
    private final AtomicReference<JsonNode> payload = new AtomicReference<>();

    public FakeTestMunicipalParkingAdapter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.payload.set(objectMapper.createArrayNode());
    }

    /** Replace the in-memory feed. Not thread-safe across concurrent syncs by design (tests). */
    public void setPayload(JsonNode next) {
        payload.set(Objects.requireNonNull(next, "payload"));
    }

    public JsonNode seedFacility(
            String externalId,
            String name,
            double lat,
            double lng,
            Integer capacity,
            Integer available,
            Integer occupied) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("externalId", externalId);
        row.put("name", name);
        row.put("lat", lat);
        row.put("lng", lng);
        if (capacity != null) {
            row.put("capacity", capacity);
        }
        if (available != null || occupied != null) {
            ObjectNode occupancy = objectMapper.createObjectNode();
            if (available != null) {
                occupancy.put("available", available);
            }
            if (occupied != null) {
                occupancy.put("occupied", occupied);
            }
            if (capacity != null) {
                occupancy.put("capacity", capacity);
            }
            row.set("occupancy", occupancy);
        }
        ArrayNode array = objectMapper.createArrayNode();
        array.add(row);
        setPayload(array);
        return array;
    }

    @Override
    public String sourceKey() {
        return SOURCE_KEY;
    }

    @Override
    public ParkingDataProviderId providerId() {
        return ParkingDataProviderId.FAKE_TEST;
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
        return payload.get();
    }

    @Override
    public SchemaFingerprint validateContract(JsonNode body) {
        SchemaFingerprint fingerprint = SchemaFingerprint.fromArray(body);
        if (!fingerprint.fields().containsAll(REQUIRED)) {
            throw new IllegalArgumentException("FAKE_TEST contract missing required keys");
        }
        return fingerprint;
    }

    @Override
    public List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode body, Instant fetchedAt) {
        List<NormalizedMunicipalFacility> out = new ArrayList<>();
        for (JsonNode row : body) {
            if (!row.hasNonNull("externalId") || !row.hasNonNull("name")
                    || !row.has("lat") || !row.has("lng")) {
                continue;
            }
            String externalId = row.get("externalId").asText().trim();
            if (externalId.isEmpty()) {
                continue;
            }
            double lat = row.get("lat").asDouble();
            double lng = row.get("lng").asDouble();
            if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
                continue;
            }
            Integer capacity = row.hasNonNull("capacity") ? row.get("capacity").asInt() : null;
            out.add(new NormalizedMunicipalFacility(
                    externalId,
                    "Fake Operator",
                    MunicipalFacilityType.OFF_STREET,
                    row.get("name").asText(),
                    null,
                    lat,
                    lng,
                    capacity,
                    MunicipalAccessClassification.PUBLIC,
                    Map.of("provider", ParkingDataProviderId.FAKE_TEST.name()),
                    "fake-" + externalId));
        }
        return out;
    }

    @Override
    public List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode body, Instant fetchedAt) {
        List<NormalizedMunicipalOccupancy> out = new ArrayList<>();
        for (JsonNode row : body) {
            if (!row.hasNonNull("externalId") || !row.has("occupancy")) {
                continue;
            }
            JsonNode occupancy = row.get("occupancy");
            String externalId = row.get("externalId").asText().trim();
            if (externalId.isEmpty()) {
                continue;
            }
            Integer capacity = occupancy.hasNonNull("capacity")
                    ? occupancy.get("capacity").asInt()
                    : (row.hasNonNull("capacity") ? row.get("capacity").asInt() : null);
            Integer available = occupancy.hasNonNull("available") ? occupancy.get("available").asInt() : null;
            Integer occupied = occupancy.hasNonNull("occupied") ? occupancy.get("occupied").asInt() : null;
            out.add(new NormalizedMunicipalOccupancy(
                    externalId,
                    fetchedAt,
                    fetchedAt,
                    MunicipalTimestampProvenance.FETCH,
                    capacity,
                    occupied,
                    available,
                    MunicipalOccupancyFreshness.LIVE,
                    "fake-occ-" + externalId));
        }
        return out;
    }
}
