package com.parkio.parking.infrastructure.kayseri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.client.KayseriParkingClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class KayseriMunicipalParkingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private KayseriParkingClient client;
    private KayseriMunicipalParkingAdapter adapter;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(KayseriParkingClient.class);
        adapter = new KayseriMunicipalParkingAdapter(
                client,
                mapper,
                new KayseriRecordValidator(new KayseriGeoValidator()),
                new KayseriNormalizer(mapper));
    }

    @Test
    void declaresInventoryOnlyUpsertCapabilities() {
        assertThat(adapter.sourceKey()).isEqualTo("kayseri-bb-otoparklar");
        assertThat(adapter.providerId()).isEqualTo(ParkingDataProviderId.KAYSERI);
        assertThat(adapter.capabilities()).containsExactly(ProviderCapability.FACILITY_INVENTORY);
        assertThat(adapter.capabilities()).doesNotContain(ProviderCapability.LIVE_OCCUPANCY);
        assertThat(adapter.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
    }

    @Test
    void normalizesGeoJsonFlatRowsWithTurkishUtf8AndRejectsBadCoords() throws Exception {
        JsonNode geojson = mapper.readTree(getClass().getResourceAsStream(
                "/fixtures/municipal/kayseri/sample-geojson.json"));
        JsonNode payload = new KayseriParkingClient(
                        org.springframework.web.client.RestClient.builder(),
                        mapper,
                        new com.parkio.parking.infrastructure.config.MunicipalSourceProperties())
                .flattenFeatures(geojson);

        Instant now = Instant.parse("2026-08-12T18:00:00Z");
        var facilities = adapter.normalizeFacilities(payload, now);
        var occupancy = adapter.normalizeOccupancy(payload, now);

        assertThat(facilities).hasSize(3);
        assertThat(facilities).extracting(f -> f.externalId())
                .containsExactlyInAnyOrder("2723", "2724", "2726");
        assertThat(facilities).noneMatch(f -> "9999".equals(f.externalId()));
        assertThat(occupancy).isEmpty();

        var tacettin = facilities.stream().filter(f -> "2723".equals(f.externalId())).findFirst().orElseThrow();
        assertThat(tacettin.displayName()).isEqualTo("TACETTİN VELİ KATLI OTOPARKI");
        assertThat(tacettin.displayName()).contains("İ");
        assertThat(tacettin.capacityTotal()).isNull();
        assertThat(tacettin.facilityType()).isEqualTo(MunicipalFacilityType.UNKNOWN);
        assertThat(tacettin.latitude()).isEqualTo(38.715748);
        assertThat(tacettin.longitude()).isEqualTo(35.491699);

        var yogunburc = facilities.stream().filter(f -> "2726".equals(f.externalId())).findFirst().orElseThrow();
        assertThat(yogunburc.displayName()).isEqualTo("YOĞUNBURÇ OTOPARKI");
        assertThat(yogunburc.displayName()).contains("Ğ");
        assertThat(yogunburc.displayName()).contains("Ç");
    }

    @Test
    void skipsDuplicateIdsAndMissingIds() throws Exception {
        JsonNode payload = mapper.readTree("""
                [
                  {"CBNO":2723,"ADI":"A","lat_DD":38.72,"lon_DD":35.49},
                  {"CBNO":2723,"ADI":"Dup","lat_DD":38.72,"lon_DD":35.49},
                  {"CBNO":null,"ADI":"NoId","lat_DD":38.72,"lon_DD":35.49},
                  {"CBNO":2724,"ADI":"B","lat_DD":38.72,"lon_DD":35.49}
                ]
                """);
        var facilities = adapter.normalizeFacilities(payload, Instant.parse("2026-08-12T18:00:00Z"));
        assertThat(facilities).extracting(f -> f.externalId()).containsExactlyInAnyOrder("2723", "2724");
        assertThat(adapter.normalizeOccupancy(payload, Instant.parse("2026-08-12T18:00:00Z"))).isEmpty();
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
        JsonNode payload = mapper.readTree("[{\"CBNO\":\"1\",\"ADI\":\"x\"}]");
        assertThatThrownBy(() -> adapter.validateContract(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Kayseri contract");
    }
}
