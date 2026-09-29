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
        assertThat(view.occupiedSpaces()).isEqualTo(20);
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

    @Test
    void closedIsparkOmitsOccupancyOnAuthenticatedFindAndNearby() {
        UUID id = UUID.fromString("81279bd3-5c60-42a1-81bc-8255e22a1a48");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var closed = facility(
                id, "Avcılar İdo", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":0,\"district\":\"AVCILAR\"}");
        when(facilities.findById(id)).thenReturn(Optional.of(closed));
        when(facilities.nearby(40.9712, 28.7185, 1000, 10)).thenReturn(List.of(closed));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var byId = service.findById(id).orElseThrow();
        var nearby = service.nearby(40.9712, 28.7185, 1000, 10);

        assertThat(nearby).extracting(MunicipalFacilityQueryService.FacilityView::id).containsExactly(id);
        assertThat(byId.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(byId.availableSpaces()).isNull();
        assertThat(byId.occupiedSpaces()).isNull();
        assertThat(byId.capacityTotal()).isEqualTo(270);
        assertThat(nearby.getFirst().availableSpaces()).isNull();
        assertThat(nearby.getFirst().freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void openIsparkPreservesZeroAndPositiveAvailabilityOnAuthenticatedPath() {
        UUID openId = UUID.randomUUID();
        UUID zeroId = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var open = facility(
                openId, "Open lot", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":1}");
        var zero = facility(
                zeroId, "Full lot", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":true}");
        when(facilities.findById(openId)).thenReturn(Optional.of(open));
        when(facilities.findById(zeroId)).thenReturn(Optional.of(zero));
        when(snapshots.latestForFacility(openId)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 40, 80, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        when(snapshots.latestForFacility(zeroId)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        50, 50, 0, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        assertThat(service.findById(openId).orElseThrow().availableSpaces()).isEqualTo(80);
        assertThat(service.findById(openId).orElseThrow().freshness())
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(service.findById(zeroId).orElseThrow().availableSpaces()).isZero();
        assertThat(service.findById(zeroId).orElseThrow().freshness())
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
    }

    @Test
    void unknownIsparkOpenStatusOmitsAuthenticatedOccupancyAndStaysDiscoverable() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var unknown = facility(
                id, "Unknown lot", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                "{\"district\":\"AVCILAR\"}");
        when(facilities.findById(id)).thenReturn(Optional.of(unknown));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        80, 39, 41, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var view = service.findById(id).orElseThrow();
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.displayName()).isEqualTo("Unknown lot");
    }

    @Test
    void izumPlusIsparkDoesNotLetClosedIsparkHideIzumOccupancy() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var shared = facility(
                id, "Shared lot", "IZELMAN A.S.", MunicipalFacilityQueryService.IZUM_SOURCE_LABEL,
                MunicipalFacilityQueryService.IZUM_ATTRIBUTION,
                MunicipalSourceIdentity.IZUM,
                Set.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":0}");
        when(facilities.findById(id)).thenReturn(Optional.of(shared));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var view = service.findById(id).orElseThrow();
        assertThat(view.availableSpaces()).isEqualTo(90);
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(view.sourceLabel()).isEqualTo(MunicipalFacilityQueryService.IZUM_SOURCE_LABEL);
    }

    @Test
    void closedIsparkDoesNotPublishLatestSnapshotJustBecauseIzumIsLinked() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var shared = facility(
                id, "Shared lot", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK,
                Set.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":0}");
        when(facilities.findById(id)).thenReturn(Optional.of(shared));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, Instant.parse("2026-07-30T05:59:50Z"), 5L, true)));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.empty());
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var view = service.findById(id).orElseThrow();
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.occupiedSpaces()).isNull();
        assertThat(view.displayName()).isEqualTo("Shared lot");
    }

    @Test
    void openIsparkStaleSuppressionRemainsIntactOnAuthenticatedPath() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var open = facility(
                id, "Stale open", "İSPARK", MunicipalFacilityQueryService.ISPARK_SOURCE_LABEL,
                MunicipalFacilityQueryService.ISPARK_ATTRIBUTION,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                "{\"isOpen\":1}");
        when(facilities.findById(id)).thenReturn(Optional.of(open));
        when(snapshots.latestForFacility(id)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 60, 40, Instant.parse("2026-07-30T05:00:00Z"), null, true)));
        var service = service(facilities, snapshots, new MunicipalSourceProperties(), new IzelmanProperties());

        var view = service.findById(id).orElseThrow();
        assertThat(view.freshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(view.availableSpaces()).isNull();
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, String name, String operator, String sourceLabel, String attribution,
            String primaryKey, Set<String> linked) {
        return facility(id, name, operator, sourceLabel, attribution, primaryKey, linked, null);
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, String name, String operator, String sourceLabel, String attribution,
            String primaryKey, Set<String> linked, String isparkSourceMetadataJson) {
        return new MunicipalFacilityRepository.Facility(
                id, name, operator, MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                100, true, true, sourceLabel, attribution, 60, 120, primaryKey, linked,
                MunicipalAccessClassification.PUBLIC, isparkSourceMetadataJson);
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