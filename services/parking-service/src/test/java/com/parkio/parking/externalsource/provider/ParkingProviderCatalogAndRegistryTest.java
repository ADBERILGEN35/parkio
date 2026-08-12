package com.parkio.parking.externalsource.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import com.parkio.parking.infrastructure.fake.FakeTestMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
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

        ParkingDataSourceDescriptor ispark = ParkingProviderCatalog.require(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(ispark.providerId()).isEqualTo(ParkingDataProviderId.ISPARK);
        assertThat(ispark.supports(ProviderCapability.LIVE_OCCUPANCY)).isTrue();
        assertThat(ispark.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);
        assertThat(ispark.productionEligible()).isTrue();

        ParkingDataSourceDescriptor anpark = ParkingProviderCatalog.require(
                com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(anpark.providerId()).isEqualTo(ParkingDataProviderId.ANPARK);
        assertThat(anpark.supports(ProviderCapability.FACILITY_INVENTORY)).isTrue();
        assertThat(anpark.supports(ProviderCapability.LIVE_OCCUPANCY)).isFalse();
        assertThat(anpark.reconciliationMode()).isEqualTo(ReconciliationMode.AUTHORITATIVE_FULL_SET);
        assertThat(anpark.productionEligible()).isFalse();

        ParkingDataSourceDescriptor konya = ParkingProviderCatalog.require(
                com.parkio.parking.infrastructure.konya.KonyaMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(konya.providerId()).isEqualTo(ParkingDataProviderId.KONYA);
        assertThat(konya.supports(ProviderCapability.FACILITY_INVENTORY)).isTrue();
        assertThat(konya.supports(ProviderCapability.LIVE_OCCUPANCY)).isFalse();
        assertThat(konya.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
        assertThat(konya.productionEligible()).isTrue();

        ParkingDataSourceDescriptor kayseri = ParkingProviderCatalog.require(
                com.parkio.parking.infrastructure.kayseri.KayseriMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(kayseri.providerId()).isEqualTo(ParkingDataProviderId.KAYSERI);
        assertThat(kayseri.supports(ProviderCapability.FACILITY_INVENTORY)).isTrue();
        assertThat(kayseri.supports(ProviderCapability.LIVE_OCCUPANCY)).isFalse();
        assertThat(kayseri.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
        assertThat(kayseri.productionEligible()).isTrue();

        ParkingDataSourceDescriptor osm = ParkingProviderCatalog.require("osm-geofabrik-turkey");
        assertThat(osm.providerId()).isEqualTo(ParkingDataProviderId.OPENSTREETMAP);
        assertThat(osm.supports(ProviderCapability.LIVE_OCCUPANCY)).isFalse();
        assertThat(osm.reconciliationMode()).isEqualTo(ReconciliationMode.UPSERT_ONLY);
    }

    @Test
    void displayNamesRemainStableAndSeparateFromSourceKeys() {
        assertThat(ParkingProviderCatalog.IZUM_DISPLAY_NAME)
                .isEqualTo("Izmir Buyuksehir Belediyesi / IZUM");
        assertThat(ParkingProviderCatalog.ISPARK_DISPLAY_NAME)
                .isEqualTo("Istanbul Buyuksehir Belediyesi / ISPARK");
        assertThat(ParkingProviderCatalog.ANPARK_DISPLAY_NAME)
                .isEqualTo("Ankara Buyuksehir Belediyesi / ANPARK");
        assertThat(ParkingProviderCatalog.KONYA_DISPLAY_NAME)
                .isEqualTo("Konya Buyuksehir Belediyesi");
        assertThat(ParkingProviderCatalog.KAYSERI_DISPLAY_NAME)
                .isEqualTo("Kayseri Buyuksehir Belediyesi");
        assertThat(ParkingProviderCatalog.OSM_DISPLAY_NAME)
                .isEqualTo("OpenStreetMap contributors / Geofabrik GmbH");
        assertThat(ParkingProviderCatalog.require(IzumMunicipalParkingAdapter.SOURCE_KEY).displayName())
                .doesNotContain("izmir-izum");
        assertThat(ParkingProviderCatalog.require(IsparkMunicipalParkingAdapter.SOURCE_KEY).displayName())
                .doesNotContain("istanbul-ispark");
        assertThat(ParkingProviderCatalog.require(
                        com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter.SOURCE_KEY)
                .displayName())
                .doesNotContain("ankara-anpark");
    }

    @Test
    void liveOccupancyAuthorityPrefersIzumThenIspark() {
        assertThat(ParkingProviderCatalog.liveOccupancyAuthoritySource(
                        java.util.Set.of(
                                IzumMunicipalParkingAdapter.SOURCE_KEY,
                                IsparkMunicipalParkingAdapter.SOURCE_KEY)))
                .contains(IzumMunicipalParkingAdapter.SOURCE_KEY);
        assertThat(ParkingProviderCatalog.liveOccupancyAuthoritySource(
                        java.util.Set.of(IsparkMunicipalParkingAdapter.SOURCE_KEY)))
                .contains(IsparkMunicipalParkingAdapter.SOURCE_KEY);
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
