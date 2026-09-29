package com.parkio.parking.infrastructure.ispark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.infrastructure.client.IsparkParkingClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IsparkMunicipalParkingAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private IsparkMunicipalParkingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IsparkMunicipalParkingAdapter(
                Mockito.mock(IsparkParkingClient.class),
                mapper,
                new IsparkRecordValidator(),
                new IsparkNormalizer(mapper));
    }

    @Test
    void declaresCatalogAlignedCapabilities() {
        assertThat(adapter.sourceKey()).isEqualTo("istanbul-ispark-parks");
        assertThat(adapter.providerId()).isEqualTo(ParkingDataProviderId.ISPARK);
        assertThat(adapter.capabilities()).containsExactlyInAnyOrder(
                ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY);
        assertThat(adapter.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);
    }

    @Test
    void normalizesSampleAndSkipsDuplicatesAndInvalids() throws Exception {
        String json = """
                [
                  {"parkID":1,"parkName":"A","lat":"41.0","lng":"29.0","capacity":10,"emptyCapacity":3,
                   "workHours":"24 Saat","parkType":"AÇIK OTOPARK","freeTime":0,"district":"KADIKÖY","isOpen":1},
                  {"parkID":1,"parkName":"Dup","lat":"41.0","lng":"29.0","capacity":10,"emptyCapacity":1,
                   "workHours":"24 Saat","parkType":"AÇIK OTOPARK","freeTime":0,"district":"KADIKÖY","isOpen":1},
                  {"parkID":2,"parkName":"","lat":"41.0","lng":"29.0","capacity":10,"emptyCapacity":1,
                   "workHours":"24 Saat","parkType":"AÇIK OTOPARK","freeTime":0,"district":"KADIKÖY","isOpen":1},
                  {"parkID":3,"parkName":"Road","lat":"41.1","lng":"29.1","capacity":20,"emptyCapacity":5,
                   "workHours":"08-20","parkType":"YOL ÜSTÜ","freeTime":0,"district":"ŞİŞLİ","isOpen":1}
                ]
                """;
        JsonNode payload = mapper.readTree(json);
        Instant now = Instant.parse("2026-08-07T10:00:00Z");

        var facilities = adapter.normalizeFacilities(payload, now);
        var occupancy = adapter.normalizeOccupancy(payload, now);

        assertThat(facilities).hasSize(2);
        assertThat(facilities).extracting(f -> f.externalId()).containsExactly("1", "3");
        assertThat(occupancy).hasSize(2);
        assertThat(occupancy.get(0).availableSpaces()).isEqualTo(3);
        adapter.validateContract(payload);
    }

    @Test
    void rejectsContractMissingRequiredFields() throws Exception {
        JsonNode payload = mapper.readTree("[{\"parkID\":1,\"parkName\":\"x\"}]");
        assertThatThrownBy(() -> adapter.validateContract(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISPARK contract");
    }
}
