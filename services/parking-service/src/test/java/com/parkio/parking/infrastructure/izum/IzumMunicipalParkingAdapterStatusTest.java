package com.parkio.parking.infrastructure.izum;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// CL-F22, owner decision 2026-10-05: unknown IZUM statuses are reported; what is published does not change.
class IzumMunicipalParkingAdapterStatusTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-10-05T08:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IzumMunicipalParkingAdapter adapter = new IzumMunicipalParkingAdapter(
            null, mapper, new IzumRecordValidator(), new IzumNormalizer(mapper), new IzumStatusObserver(registry));

    @Test void reportsUnknownStatusesAndPublishesTheRecordsAsBefore() throws IOException {
        List<NormalizedMunicipalOccupancy> before = adapter.normalizeOccupancy(fixture(), FETCHED_AT);
        JsonNode payload = fixture();
        ((ObjectNode) payload.get(0)).put("status", "Kapalı");
        ((ObjectNode) payload.get(1)).put("status", "Maintenance");
        ((ObjectNode) payload.get(2)).remove("status");

        List<NormalizedMunicipalOccupancy> after = adapter.normalizeOccupancy(payload, FETCHED_AT);

        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i).occupancyStatus()).as("record %d", i).isEqualTo(before.get(i).occupancyStatus());
            assertThat(after.get(i).availableSpaces()).as("record %d", i).isEqualTo(before.get(i).availableSpaces());
        }
        assertThat(count("unrecognised")).isEqualTo(2.0);
        assertThat(count("missing")).isEqualTo(1.0);
    }

    @Test void invalidRecordsAreNotObserved() throws IOException {
        JsonNode payload = fixture();
        ((ObjectNode) payload.get(0)).put("status", "Kapalı").put("lat", 123.0);

        adapter.normalizeOccupancy(payload, FETCHED_AT);

        assertThat(count("unrecognised")).isZero();
    }

    private JsonNode fixture() throws IOException {
        return mapper.readTree(getClass().getResourceAsStream("/fixtures/municipal/izum/otoparklar-sample.json"));
    }

    private double count(String kind) {
        Counter counter = registry.find(IzumStatusObserver.METRIC).tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }
}
