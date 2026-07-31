package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
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

class MunicipalFacilityQueryServiceTest {
    @Test
    void staleOccupancyNeverExposesAvailableSpaces() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Facility", "IZELMAN", "IZUM",
                MunicipalFacilityQueryService.IZUM_ATTRIBUTION,
                MunicipalSourceIdentity.IZUM, Set.of(MunicipalSourceIdentity.IZUM));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 60, 40, Instant.parse("2026-07-30T05:00:00Z"), null, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var result = service.findById(id).orElseThrow();

        assertThat(result.freshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(result.availableSpaces()).isNull();
        assertThat(result.capacityTotal()).isEqualTo(100);
    }

    @Test
    void izumAttributionContainingIzelmanDoesNotHideFacility() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Live lot", "IZELMAN A.S.", "IZELMAN A.S.",
                MunicipalFacilityQueryService.IZUM_ATTRIBUTION,
                MunicipalSourceIdentity.IZUM, Set.of(MunicipalSourceIdentity.IZUM));
        when(facilities.nearby(38.4, 27.1, 1000, 10)).thenReturn(List.of(facility));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 20, 80, Instant.parse("2026-07-30T05:59:50Z"), null, true)));

        var izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), izelman);

        assertThat(service.nearby(38.4, 27.1, 1000, 10)).hasSize(1);
        var view = service.findById(id).orElseThrow();
        assertThat(view.availableSpaces()).isEqualTo(80);
        assertThat(view.attribution()).contains("IZELMAN");
        assertThat(view.sourceLabel()).isEqualTo(MunicipalFacilityQueryService.IZUM_SOURCE_LABEL);
    }

    @Test
    void izumPublisherTextWithIzelmanWordingStillUsesStableIzumIdentity() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Live lot", "provider", "Some IZELMAN wording in label",
                "unrelated",
                MunicipalSourceIdentity.IZUM, Set.of(MunicipalSourceIdentity.IZUM));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.empty());

        var izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), izelman);

        assertThat(service.findById(id)).isPresent();
    }

    @Test
    void osmFacilitiesHiddenWhenPublicationDisabled() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var osm = facility(
                id, "OSM lot", null, "OpenStreetMap contributors / Geofabrik GmbH",
                "OpenStreetMap contributors",
                MunicipalSourceIdentity.OSM, Set.of(MunicipalSourceIdentity.OSM));
        when(facilities.nearby(38.4, 27.1, 1000, 10)).thenReturn(List.of(osm));
        when(facilities.findById(id)).thenReturn(Optional.of(osm));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.empty());

        var props = new MunicipalSourceProperties();
        props.getOsm().setPublicationEnabled(false);
        var service = service(facilities, snapshots, props, new IzelmanProperties());

        assertThat(service.nearby(38.4, 27.1, 1000, 10)).isEmpty();
        assertThat(service.findById(id)).isEmpty();

        props.getOsm().setPublicationEnabled(true);
        assertThat(service.nearby(38.4, 27.1, 1000, 10)).hasSize(1);
        assertThat(service.findById(id)).isPresent();
        assertThat(service.findById(id).orElseThrow().availableSpaces()).isNull();
        assertThat(service.findById(id).orElseThrow().freshness())
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void osmUnaffectedByIzelmanPublicationFlag() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var osm = facility(
                id, "OSM lot", null, "OpenStreetMap contributors / Geofabrik GmbH",
                "OpenStreetMap contributors",
                MunicipalSourceIdentity.OSM, Set.of(MunicipalSourceIdentity.OSM));
        when(facilities.findById(id)).thenReturn(Optional.of(osm));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.empty());

        var props = new MunicipalSourceProperties();
        props.getOsm().setPublicationEnabled(true);
        var izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, props, izelman);

        assertThat(service.findById(id)).isPresent();
        assertThat(service.findById(id).orElseThrow().availableSpaces()).isNull();
    }

    @Test
    void izelmanFacilitiesHiddenWhenPublicationDisabledAndNeverExposeOccupancy() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var izelmanFacility = facility(
                id, "IZELMAN lot", "IZELMAN A.S.", "IZELMAN A.S.",
                "Izmir Metropolitan Municipality / IZELMAN A.S.",
                IzelmanSourceKeys.OPEN, Set.of(IzelmanSourceKeys.OPEN));
        when(facilities.nearby(38.4, 27.1, 1000, 10)).thenReturn(List.of(izelmanFacility));
        when(facilities.findById(id)).thenReturn(Optional.of(izelmanFacility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        80, 10, 70, Instant.parse("2026-07-30T05:00:00Z"), null, true)));

        var izelmanProps = new IzelmanProperties();
        izelmanProps.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), izelmanProps);
        assertThat(service.nearby(38.4, 27.1, 1000, 10)).isEmpty();
        assertThat(service.findById(id)).isEmpty();

        izelmanProps.setFacilityPublicationEnabled(true);
        var view = service.findById(id).orElseThrow();
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void multiSourceIzumPlusIzelmanRemainsVisibleWithLiveOccupancyWhenIzelmanGated() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Shared lot", "IZELMAN A.S.", "IZELMAN A.S.",
                "Izmir Metropolitan Municipality / IZELMAN A.S.",
                IzelmanSourceKeys.OPEN,
                Set.of(MunicipalSourceIdentity.IZUM, IzelmanSourceKeys.OPEN));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, Instant.parse("2026-07-30T05:59:50Z"), null, true)));

        var izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), izelman);

        var view = service.findById(id).orElseThrow();
        assertThat(view.availableSpaces()).isEqualTo(90);
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(view.sourceLabel()).isEqualTo(MunicipalFacilityQueryService.IZUM_SOURCE_LABEL);
        assertThat(view.attribution()).contains("IZELMAN");
        assertThat(view.capacityTotal()).isEqualTo(120);
    }

    @Test
    void multiSourceOsmPlusIzelmanRemainsVisibleThroughOsmWhenIzelmanGated() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Shared OSM", "IZELMAN A.S.", "IZELMAN A.S.",
                "Izmir Metropolitan Municipality / IZELMAN A.S.",
                IzelmanSourceKeys.CLOSED,
                Set.of(MunicipalSourceIdentity.OSM, IzelmanSourceKeys.CLOSED));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.empty());

        var props = new MunicipalSourceProperties();
        props.getOsm().setPublicationEnabled(true);
        var izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        var service = service(facilities, snapshots, props, izelman);

        var view = service.findById(id).orElseThrow();
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.sourceLabel()).isEqualTo(MunicipalFacilityQueryService.OSM_SOURCE_LABEL);
        assertThat(view.attribution()).doesNotContain("IZELMAN A.S.");
    }

    @Test
    void sourceDisplayLabelRenameDoesNotChangePublicationDecision() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = facility(
                id, "Renamed publisher facility", "x", "TOTALLY DIFFERENT LABEL",
                "also different attribution without OSM marker",
                MunicipalSourceIdentity.OSM, Set.of(MunicipalSourceIdentity.OSM));
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.empty());

        var props = new MunicipalSourceProperties();
        props.getOsm().setPublicationEnabled(false);
        assertThat(service(facilities, snapshots, props, new IzelmanProperties()).findById(id)).isEmpty();

        props.getOsm().setPublicationEnabled(true);
        assertThat(service(facilities, snapshots, props, new IzelmanProperties()).findById(id)).isPresent();
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, String name, String operator, String sourceLabel, String attribution,
            String primaryKey, Set<String> linked) {
        return new MunicipalFacilityRepository.Facility(
                id, name, operator, MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                100, true, true, sourceLabel, attribution, 60, 120, primaryKey, linked,
                MunicipalAccessClassification.PUBLIC);
    }

    private static MunicipalFacilityQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipal,
            IzelmanProperties izelman) {
        // Isolate non-discovery tests from DATA-WP-12 Java field default-on.
        municipal.getDiscovery().setDuplicatePresentationEnabled(false);
        return new MunicipalFacilityQueryService(
                facilities, snapshots, municipal, izelman,
                Clock.fixed(Instant.parse("2026-07-30T06:00:00Z"), ZoneOffset.UTC));
    }
}