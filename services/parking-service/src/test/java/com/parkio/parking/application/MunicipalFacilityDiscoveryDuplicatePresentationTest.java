package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MunicipalFacilityDiscoveryDuplicatePresentationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-30T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void flagFalseFetchesExactLimitAndKeepsBothPeers() {
        UUID izumId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID osmId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var izum = facility(
                izumId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.IZUM,
                38.42000, 27.14000, 100);
        var osm = facility(
                osmId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.OSM,
                38.42005, 27.14005, 100);
        when(facilities.nearby(38.42, 27.14, 1000, 10)).thenReturn(List.of(izum, osm));
        when(snapshots.latestForFacility(izumId)).thenReturn(Optional.of(liveSnapshot()));
        when(snapshots.latestForFacility(osmId)).thenReturn(Optional.empty());

        MunicipalSourceProperties props = props(false);
        props.getOsm().setPublicationEnabled(true);
        var service = service(facilities, snapshots, props);

        List<MunicipalFacilityQueryService.FacilityView> result =
                service.nearby(38.42, 27.14, 1000, 10);

        assertThat(result).extracting(MunicipalFacilityQueryService.FacilityView::id)
                .containsExactly(izumId, osmId);
        verify(facilities).nearby(38.42, 27.14, 1000, 10);
    }

    @Test
    void flagTrueSuppressesStrongOsmDuplicatePreferringLiveIzum() {
        UUID izumId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID osmId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var izum = facility(
                izumId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.IZUM,
                38.42000, 27.14000, 100);
        var osm = facility(
                osmId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.OSM,
                38.42005, 27.14005, 100);
        when(facilities.nearby(eq(38.42), eq(27.14), eq(1000), eq(20))).thenReturn(List.of(izum, osm));
        when(facilities.findById(osmId)).thenReturn(Optional.of(osm));
        when(snapshots.latestForFacility(izumId)).thenReturn(Optional.of(liveSnapshot()));
        when(snapshots.latestForFacility(osmId)).thenReturn(Optional.empty());

        MunicipalSourceProperties props = props(true);
        props.getOsm().setPublicationEnabled(true);
        var service = service(facilities, snapshots, props);

        List<MunicipalFacilityQueryService.FacilityView> result =
                service.nearby(38.42, 27.14, 1000, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(izumId);
        assertThat(result.get(0).availableSpaces()).isEqualTo(80);
        assertThat(result.get(0).attribution()).contains("CC BY 4.0");
        assertThat(service.findById(osmId)).isPresent();
        assertThat(service.findById(osmId).orElseThrow().availableSpaces()).isNull();
        assertThat(service.findById(osmId).orElseThrow().attribution())
                .isEqualTo(MunicipalFacilityQueryService.OSM_ATTRIBUTION);
    }

    @Test
    void staleIzumWinsOverOsmWithNullAvailability() {
        UUID izumId = UUID.fromString("00000000-0000-0000-0000-000000000121");
        UUID osmId = UUID.fromString("00000000-0000-0000-0000-000000000122");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var izum = facility(
                izumId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.IZUM,
                38.42000, 27.14000, 100);
        var osm = facility(
                osmId, "Konak Otoparki", "IZELMAN", MunicipalSourceIdentity.OSM,
                38.42005, 27.14005, 100);
        when(facilities.nearby(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of(izum, osm));
        when(snapshots.latestForFacility(izumId)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 60, 40, Instant.parse("2026-07-30T05:00:00Z"), null, true)));
        when(snapshots.latestForFacility(osmId)).thenReturn(Optional.empty());

        MunicipalSourceProperties props = props(true);
        props.getOsm().setPublicationEnabled(true);
        var service = service(facilities, snapshots, props);

        var result = service.nearby(38.42, 27.14, 1000, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(izumId);
        assertThat(result.get(0).freshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(result.get(0).availableSpaces()).isNull();
    }

    @Test
    void osmRemainsWhenMunicipalPeerUnpublished() {
        UUID osmId = UUID.fromString("00000000-0000-0000-0000-000000000131");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var osm = facility(
                osmId, "OSM Only", null, MunicipalSourceIdentity.OSM,
                38.42000, 27.14000, 40);
        when(facilities.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(osm));
        when(snapshots.latestForFacility(osmId)).thenReturn(Optional.empty());

        MunicipalSourceProperties props = props(true);
        props.getOsm().setPublicationEnabled(true);
        var service = service(facilities, snapshots, props);

        var result = service.nearby(38.42, 27.14, 1000, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).availableSpaces()).isNull();
        assertThat(result.get(0).freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(result.get(0).attribution()).isEqualTo(MunicipalFacilityQueryService.OSM_ATTRIBUTION);
    }

    @Test
    void overfetchUsesBoundedLimitThenAppliesRequestedPageSize() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());

        MunicipalSourceProperties props = props(true);
        props.getDiscovery().setOverfetchFactor(2);
        props.getDiscovery().setOverfetchAbsoluteMax(200);
        var service = service(facilities, snapshots, props);
        service.nearby(38.42, 27.14, 1000, 50);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(facilities, times(1)).nearby(eq(38.42), eq(27.14), eq(1000), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(100);
    }

    private static MunicipalOccupancySnapshotRepository.Snapshot liveSnapshot() {
        return new MunicipalOccupancySnapshotRepository.Snapshot(
                100, 20, 80, Instant.parse("2026-07-30T05:59:50Z"), null, true);
    }

    private static MunicipalSourceProperties props(boolean enabled) {
        MunicipalSourceProperties props = new MunicipalSourceProperties();
        props.getDiscovery().setDuplicatePresentationEnabled(enabled);
        props.getDiscovery().setDuplicateRadiusMeters(100);
        props.getDiscovery().setOverfetchFactor(2);
        props.getDiscovery().setOverfetchAbsoluteMax(200);
        props.getDiscovery().setSupportedPairs(List.of("IZUM_OSM"));
        return props;
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, String name, String operator, String sourceKey,
            double lat, double lng, Integer capacity) {
        return new MunicipalFacilityRepository.Facility(
                id, name, operator, MunicipalFacilityType.OFF_STREET, "Konak",
                lat, lng, capacity, true, true,
                MunicipalSourceIdentity.IZUM.equals(sourceKey)
                        ? MunicipalFacilityQueryService.IZUM_SOURCE_LABEL
                        : MunicipalFacilityQueryService.OSM_SOURCE_LABEL,
                MunicipalSourceIdentity.IZUM.equals(sourceKey)
                        ? MunicipalFacilityQueryService.IZUM_ATTRIBUTION
                        : MunicipalFacilityQueryService.OSM_ATTRIBUTION,
                60, 120, sourceKey, Set.of(sourceKey), MunicipalAccessClassification.PUBLIC);
    }

    private static MunicipalFacilityQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipal) {
        return new MunicipalFacilityQueryService(
                facilities, snapshots, municipal, new IzelmanProperties(), CLOCK);
    }
}
