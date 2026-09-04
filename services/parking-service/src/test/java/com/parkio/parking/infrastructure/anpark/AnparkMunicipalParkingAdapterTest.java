package com.parkio.parking.infrastructure.anpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.client.AnparkParkingClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AnparkMunicipalParkingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AnparkMunicipalParkingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AnparkMunicipalParkingAdapter(
                Mockito.mock(AnparkParkingClient.class),
                mapper,
                new AnparkRecordValidator(),
                new AnparkNormalizer(mapper));
    }

    @Test
    void declaresInventoryOnlyCatalogAlignedCapabilities() {
        assertThat(adapter.sourceKey()).isEqualTo("ankara-anpark-parks");
        assertThat(adapter.providerId()).isEqualTo(ParkingDataProviderId.ANPARK);
        assertThat(adapter.capabilities()).containsExactly(ProviderCapability.FACILITY_INVENTORY);
        assertThat(adapter.capabilities()).doesNotContain(ProviderCapability.LIVE_OCCUPANCY);
        assertThat(adapter.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);
    }

    @Test
    void normalizesRepresentativePayloadSkipsInactiveAndNeverCreatesOccupancy() throws Exception {
        JsonNode payload = mapper.readTree(getClass().getResourceAsStream(
                "/fixtures/municipal/anpark/park-sample.json"));
        Instant now = Instant.parse("2026-08-12T10:00:00Z");

        JsonNode activeOnly = adapter.excludeInactive(payload);
        assertThat(activeOnly).hasSize(3);

        var facilities = adapter.normalizeFacilities(activeOnly, now);
        var occupancy = adapter.normalizeOccupancy(activeOnly, now);

        assertThat(facilities).hasSize(3);
        assertThat(facilities).extracting(f -> f.externalId()).containsExactlyInAnyOrder("1095", "1098", "1118");
        assertThat(facilities).noneMatch(f -> "1096".equals(f.externalId()));
        assertThat(occupancy).isEmpty();

        var zeroCap = facilities.stream().filter(f -> "1098".equals(f.externalId())).findFirst().orElseThrow();
        assertThat(zeroCap.capacityTotal()).isNull();
        assertThat(zeroCap.facilityType()).isEqualTo(MunicipalFacilityType.OFF_STREET);

        var street = facilities.stream().filter(f -> "1095".equals(f.externalId())).findFirst().orElseThrow();
        assertThat(street.facilityType()).isEqualTo(MunicipalFacilityType.ON_STREET);
        assertThat(street.capacityTotal()).isEqualTo(16);

        var closed = facilities.stream().filter(f -> "1118".equals(f.externalId())).findFirst().orElseThrow();
        assertThat(closed.facilityType()).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(closed.capacityTotal()).isEqualTo(186);
    }

    @Test
    void skipsInvalidCoordinatesBlankNameMissingIdNegativeCapacityAndUnknownTypeStillAccepted() throws Exception {
        String json = """
                [
                  {"id":"1","name":"Good","type":"acik","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":10,"schedule":"08-18","address":"A","active":true},
                  {"id":"","name":"NoId","type":"acik","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":10,"schedule":"08-18","address":"A","active":true},
                  {"id":"2","name":"","type":"acik","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":10,"schedule":"08-18","address":"A","active":true},
                  {"id":"3","name":"BadLat","type":"acik","district":"Çankaya","lat":0.0,"lng":32.8,
                   "capacity":10,"schedule":"08-18","address":"A","active":true},
                  {"id":"4","name":"NegCap","type":"acik","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":-1,"schedule":"08-18","address":"A","active":true},
                  {"id":"5","name":"FutureType","type":"brand_new_kind","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":12,"schedule":"08-18","address":"A","active":true},
                  {"id":"1","name":"Dup","type":"acik","district":"Çankaya","lat":39.9,"lng":32.8,
                   "capacity":10,"schedule":"08-18","address":"A","active":true}
                ]
                """;
        JsonNode payload = mapper.readTree(json);
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        var facilities = adapter.normalizeFacilities(payload, now);
        assertThat(facilities).hasSize(2);
        assertThat(facilities).extracting(f -> f.externalId()).containsExactlyInAnyOrder("1", "5");
        assertThat(facilities.stream().filter(f -> "5".equals(f.externalId())).findFirst().orElseThrow()
                .facilityType()).isEqualTo(MunicipalFacilityType.UNKNOWN);
        assertThat(adapter.normalizeOccupancy(payload, now)).isEmpty();
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
        JsonNode payload = mapper.readTree("[{\"id\":\"1\",\"name\":\"x\"}]");
        assertThatThrownBy(() -> adapter.validateContract(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANPARK contract");
    }

    @Test
    void allInactiveFeedPreservesPayloadForAuthoritativeTrustAndCountsValidIds() throws Exception {
        JsonNode payload = mapper.readTree("""
                [
                  {"id":"1095","name":"ALSANCAK YOL BOYU OTOPARKI","type":"yolustu","district":"Altındağ",
                   "lat":39.941283987606035,"lng":32.855654018215056,"capacity":16,
                   "schedule":"Haftanın her günü 08:00-18:00","address":"Alsancak Mahallesi, Altındağ/Ankara","active":false},
                  {"id":"1098","name":"GÖKSU PARKI OTOPARKI","type":"rekreasyon","district":"Etimesgut",
                   "lat":39.988505,"lng":32.647731,"capacity":0,
                   "schedule":"Haftanın her günü 08:00-22:00","address":"Göksu Parkı, Etimesgut/Ankara","active":false},
                  {"id":"1118","name":"SIHHIYE ÇOK KATLI OTOPARKI","type":"kapali","district":"Çankaya",
                   "lat":39.926417,"lng":32.859245,"capacity":186,
                   "schedule":"Haftanın her günü 00:00-24:00","address":"Sıhhiye, Çankaya/Ankara","active":false}
                ]
                """);
        JsonNode preserved = adapter.excludeInactive(payload);
        assertThat(preserved).hasSize(3);
        assertThat(adapter.countAuthoritativeValidUniqueFacilityExternalIds(preserved)).isEqualTo(3);
        assertThat(adapter.normalizeFacilities(preserved, Instant.parse("2026-08-12T10:00:00Z"))).isEmpty();
    }

    @Test
    void excludeInactiveRemovesExplicitFalseOnly() throws Exception {
        JsonNode payload = mapper.readTree("""
                [
                  {"id":"1","active":true},
                  {"id":"2","active":false},
                  {"id":"3"}
                ]
                """);
        JsonNode filtered = adapter.excludeInactive(payload);
        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).path("id").asText()).isEqualTo("1");
        assertThat(filtered.get(1).path("id").asText()).isEqualTo("3");
    }
}
