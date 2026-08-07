package com.parkio.parking.externalsource.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.infrastructure.fake.FakeTestMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParkingProviderCatalogAndRegistryTest {

    @Test
    void catalogSeparatesProviderSourceAndCapabilities() {
        ParkingDataSourceDescriptor izum = ParkingProviderCatalog.require(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(izum.providerId()).isEqualTo(ParkingDataProviderId.IZUM);
        assertThat(izum.sourceKey()).isEqualTo(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(izum.supports(ProviderCapability.LIVE_OCCUPANCY)).isTrue();
        assertThat(izum.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);

        ParkingDataSourceDescriptor osm = ParkingProviderCatalog.require("osm-geofabrik-turkey");
        assertThat(osm.providerId()).isEqualTo(ParkingDataProviderId.OPENSTREETMAP);
        assertThat(osm.supports(ProviderCapability.LIVE_OCCUPANCY)).isFalse();
        assertThat(osm.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
    }

    @Test
    void displayNamesRemainStableAndSeparateFromSourceKeys() {
        assertThat(ParkingProviderCatalog.IZUM_DISPLAY_NAME)
                .isEqualTo("Izmir Buyuksehir Belediyesi / IZUM");
        assertThat(ParkingProviderCatalog.OSM_DISPLAY_NAME)
                .isEqualTo("OpenStreetMap contributors / Geofabrik GmbH");
        assertThat(ParkingProviderCatalog.require(IzumMunicipalParkingAdapter.SOURCE_KEY).displayName())
                .doesNotContain("izmir-izum");
    }

    @Test
    void registryRejectsDuplicateSourceKeys() {
        MunicipalParkingSourceAdapter a = mock(MunicipalParkingSourceAdapter.class);
        MunicipalParkingSourceAdapter b = mock(MunicipalParkingSourceAdapter.class);
        when(a.sourceKey()).thenReturn(IzumMunicipalParkingAdapter.SOURCE_KEY);
        when(b.sourceKey()).thenReturn(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThatThrownBy(() -> new ParkingProviderRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void registryIndexesFakeAdapter() {
        FakeTestMunicipalParkingAdapter fake = new FakeTestMunicipalParkingAdapter(new ObjectMapper());
        ParkingProviderRegistry registry = new ParkingProviderRegistry(List.of(fake));
        assertThat(registry.registeredSourceKeys()).containsExactly(FakeTestMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(registry.requireAdapter(FakeTestMunicipalParkingAdapter.SOURCE_KEY).providerId())
                .isEqualTo(ParkingDataProviderId.FAKE_TEST);
    }
}
