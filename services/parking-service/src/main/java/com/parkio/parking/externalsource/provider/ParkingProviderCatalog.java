package com.parkio.parking.externalsource.provider;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.fake.FakeTestMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.kayseri.KayseriMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.konya.KonyaMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.osm.OsmGeofabrikSourceKeys;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Static catalog of known parking data sources. Adapters remain the ingestion boundary;
 * this catalog is the single place for provider/source/capability/presentation metadata.
 *
 * <p>Does not perform cross-provider facility fusion.
 */
public final class ParkingProviderCatalog {
    public static final String IZUM_DISPLAY_NAME = "Izmir Buyuksehir Belediyesi / IZUM";
    public static final String IZUM_ATTRIBUTION =
            "Includes public sector information from Izmir Buyuksehir Belediyesi Acik Veri Portali "
                    + "licensed under Attribution 4.0 International (CC BY 4.0). Parkio is not affiliated "
                    + "with or endorsed by Izmir Municipality or IZELMAN A.S.";
    /** ASCII catalog label; clients may localize diacritics for display. */
    public static final String ISPARK_DISPLAY_NAME = "Istanbul Buyuksehir Belediyesi / ISPARK";
    public static final String ISPARK_ATTRIBUTION =
            "Includes public sector information from Istanbul Buyuksehir Belediyesi Acik Veri Portali "
                    + "(ISPARK). Parkio is not affiliated with or endorsed by Istanbul Metropolitan "
                    + "Municipality or ISPARK A.S. Attribution required under IBB Acik Veri Licence.";
    /** ASCII catalog label; clients may localize diacritics for display. */
    public static final String ANPARK_DISPLAY_NAME = "Ankara Buyuksehir Belediyesi / ANPARK";
    public static final String ANPARK_ATTRIBUTION =
            "Includes facility inventory from Ankara Buyuksehir Belediyesi / ANPARK (BELTAS A.S.). "
                    + "Parkio is not affiliated with or endorsed by Ankara Metropolitan Municipality, "
                    + "BELTAS A.S., or ANPARK. LEGAL REVIEW REQUIRED for redistribution.";
    public static final String KONYA_DISPLAY_NAME = "Konya Buyuksehir Belediyesi";
    public static final String KONYA_ATTRIBUTION =
            "Includes public sector information from Konya Buyuksehir Belediyesi Acik Veri Portali "
                    + "licensed under Creative Commons Attribution 3.0 (CC BY 3.0). "
                    + "Parkio is not affiliated with or endorsed by Konya Metropolitan Municipality.";
    public static final String KAYSERI_DISPLAY_NAME = "Kayseri Buyuksehir Belediyesi";
    public static final String KAYSERI_ATTRIBUTION =
            "Includes public sector information from Kayseri Buyuksehir Belediyesi Acik Veri Portali "
                    + "licensed under Attribution 4.0 International (CC BY 4.0). "
                    + "Parkio is not affiliated with or endorsed by Kayseri Metropolitan Municipality.";
    public static final String OSM_DISPLAY_NAME = "OpenStreetMap contributors / Geofabrik GmbH";
    public static final String OSM_ATTRIBUTION = "OpenStreetMap contributors";

    private static final Map<String, ParkingDataSourceDescriptor> BY_SOURCE_KEY;

    static {
        Map<String, ParkingDataSourceDescriptor> map = new LinkedHashMap<>();
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.IZUM,
                IzumMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_IZUM,
                Set.of(ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY),
                ReconciliationMode.AUTHORITATIVE_FULL_SET,
                IZUM_DISPLAY_NAME,
                IZUM_ATTRIBUTION,
                true));
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.ISPARK,
                IsparkMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_ISPARK,
                Set.of(ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY),
                ReconciliationMode.AUTHORITATIVE_FULL_SET,
                ISPARK_DISPLAY_NAME,
                ISPARK_ATTRIBUTION,
                true));
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.ANPARK,
                AnparkMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_ANPARK,
                Set.of(ProviderCapability.FACILITY_INVENTORY),
                ReconciliationMode.AUTHORITATIVE_FULL_SET,
                ANPARK_DISPLAY_NAME,
                ANPARK_ATTRIBUTION,
                false));
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.KONYA,
                KonyaMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_KONYA,
                Set.of(ProviderCapability.FACILITY_INVENTORY),
                ReconciliationMode.UPSERT_ONLY,
                KONYA_DISPLAY_NAME,
                KONYA_ATTRIBUTION,
                true));
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.KAYSERI,
                KayseriMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_KAYSERI,
                Set.of(ProviderCapability.FACILITY_INVENTORY),
                ReconciliationMode.UPSERT_ONLY,
                KAYSERI_DISPLAY_NAME,
                KAYSERI_ATTRIBUTION,
                true));
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.OPENSTREETMAP,
                OsmGeofabrikSourceKeys.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_OSM,
                Set.of(ProviderCapability.FACILITY_INVENTORY),
                ReconciliationMode.UPSERT_ONLY,
                OSM_DISPLAY_NAME,
                OSM_ATTRIBUTION,
                true));
        for (String izelmanKey : IzelmanSourceKeys.ALL) {
            put(map, new ParkingDataSourceDescriptor(
                    ParkingDataProviderId.IZELMAN,
                    izelmanKey,
                    MunicipalSourceIdentity.FAMILY_IZELMAN,
                    Set.of(ProviderCapability.FACILITY_INVENTORY),
                    ReconciliationMode.UPSERT_ONLY,
                    "IZELMAN inventory (unpublished by default)",
                    "IZELMAN",
                    false));
        }
        put(map, new ParkingDataSourceDescriptor(
                ParkingDataProviderId.FAKE_TEST,
                FakeTestMunicipalParkingAdapter.SOURCE_KEY,
                MunicipalSourceIdentity.FAMILY_FAKE_TEST,
                Set.of(ProviderCapability.FACILITY_INVENTORY, ProviderCapability.LIVE_OCCUPANCY),
                ReconciliationMode.AUTHORITATIVE_FULL_SET,
                "Parkio Fake Test Provider",
                "Parkio test fixture — not a public data source",
                false));
        BY_SOURCE_KEY = Map.copyOf(map);
    }

    private ParkingProviderCatalog() {}

    private static void put(Map<String, ParkingDataSourceDescriptor> map, ParkingDataSourceDescriptor descriptor) {
        if (map.put(descriptor.sourceKey(), descriptor) != null) {
            throw new IllegalStateException("Duplicate source key in catalog: " + descriptor.sourceKey());
        }
    }

    public static Optional<ParkingDataSourceDescriptor> find(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_SOURCE_KEY.get(sourceKey.trim()));
    }

    public static ParkingDataSourceDescriptor require(String sourceKey) {
        return find(sourceKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown parking data source: " + sourceKey));
    }

    public static Collection<ParkingDataSourceDescriptor> all() {
        return BY_SOURCE_KEY.values();
    }

    public static boolean supportsLiveOccupancy(String sourceKey) {
        return find(sourceKey).map(d -> d.supports(ProviderCapability.LIVE_OCCUPANCY)).orElse(false);
    }

    public static boolean isAuthoritativeFullSet(String sourceKey) {
        return find(sourceKey)
                .map(d -> d.reconciliationMode() == ReconciliationMode.AUTHORITATIVE_FULL_SET)
                .orElse(false);
    }

    public static Optional<String> liveOccupancyAuthoritySource(Set<String> publishableSourceKeys) {
        if (publishableSourceKeys == null || publishableSourceKeys.isEmpty()) {
            return Optional.empty();
        }
        // Prefer İZUM for deterministic compatibility with existing API payloads.
        if (publishableSourceKeys.contains(IzumMunicipalParkingAdapter.SOURCE_KEY)
                && supportsLiveOccupancy(IzumMunicipalParkingAdapter.SOURCE_KEY)) {
            return Optional.of(IzumMunicipalParkingAdapter.SOURCE_KEY);
        }
        // Prefer İSPARK next when co-linked (rare); otherwise first LIVE_OCCUPANCY source.
        if (publishableSourceKeys.contains(IsparkMunicipalParkingAdapter.SOURCE_KEY)
                && supportsLiveOccupancy(IsparkMunicipalParkingAdapter.SOURCE_KEY)) {
            return Optional.of(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        }
        return publishableSourceKeys.stream().filter(ParkingProviderCatalog::supportsLiveOccupancy).findFirst();
    }
}
