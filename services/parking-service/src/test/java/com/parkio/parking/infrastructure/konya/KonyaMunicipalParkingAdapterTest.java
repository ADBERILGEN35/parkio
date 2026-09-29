package com.parkio.parking.infrastructure.konya;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.client.KonyaParkingClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KonyaMunicipalParkingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private KonyaMunicipalParkingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KonyaMunicipalParkingAdapter(
                Mockito.mock(KonyaParkingClient.class),
                mapper,
                new KonyaZoneAggregator(new KonyaCoordinateParser(mapper), new KonyaGeoValidator()),
                new KonyaNormalizer(mapper));
    }

    @Test
    void declaresInventoryOnlyUpsertOnlyCatalogAlignedCapabilities() {
        assertThat(adapter.sourceKey()).isEqualTo("konya-bb-otopark-bilgileri");
        assertThat(adapter.providerId()).isEqualTo(ParkingDataProviderId.KONYA);
        assertThat(adapter.capabilities()).containsExactly(ProviderCapability.FACILITY_INVENTORY);
        assertThat(adapter.capabilities()).doesNotContain(ProviderCapability.LIVE_OCCUPANCY);
        assertThat(adapter.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
    }

    @Test
    void normalizesRepresentativeFixtureAggregatesZonesAndNeverCreatesOccupancy() throws Exception {
        JsonNode payload = mapper.readTree(getClass().getResourceAsStream(
                "/fixtures/municipal/konya/sample-records.json"));
        Instant now = Instant.parse("2026-08-12T10:00:00Z");

        adapter.validateContract(payload);
        var facilities = adapter.normalizeFacilities(payload, now);
        var occupancy = adapter.normalizeOccupancy(payload, now);

        assertThat(facilities).hasSize(3);
        assertThat(facilities).extracting(f -> f.displayName())
                .containsExactlyInAnyOrder("ZİNDANKALE", "MERAM BÖLGESİ", "SELÇUKLU MERKEZ");
        assertThat(facilities).noneMatch(f -> f.displayName().contains("MEVLANA ÇARŞI"));
        assertThat(occupancy).isEmpty();

        var zindankale = facilities.stream()
                .filter(f -> "ZİNDANKALE".equals(f.displayName()))
                .findFirst()
                .orElseThrow();
        assertThat(zindankale.capacityTotal()).isEqualTo(77);
        assertThat(zindankale.latitude()).isBetween(37.87, 37.88);

        var meram = facilities.stream()
                .filter(f -> "MERAM BÖLGESİ".equals(f.displayName()))
                .findFirst()
                .orElseThrow();
        assertThat(meram.latitude()).isBetween(37.84, 37.86);
    }

    @Test
    void emptyFeedFailsContractValidation() throws Exception {
        JsonNode payload = mapper.readTree("[]");
        assertThatThrownBy(() -> adapter.validateContract(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejectsContractMissingRequiredFields() throws Exception {
        JsonNode payload = mapper.readTree("[{\"_id\":1,\"bolgeadi\":\"x\"}]");
        assertThatThrownBy(() -> adapter.validateContract(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Konya contract");
    }
}
