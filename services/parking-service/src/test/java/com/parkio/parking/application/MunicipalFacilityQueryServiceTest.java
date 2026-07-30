package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MunicipalFacilityQueryServiceTest {
    @Test
    void staleOccupancyNeverExposesAvailableSpaces() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var facility = new MunicipalFacilityRepository.Facility(
                id, "Facility", "IZELMAN", MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                100, true, true, "IZUM", "CC BY 4.0 attribution", 60, 120);
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
    void osmFacilitiesHiddenWhenPublicationDisabled() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var osm = new MunicipalFacilityRepository.Facility(
                id, "OSM lot", null, MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                null, false, false, "OpenStreetMap contributors / Geofabrik GmbH",
                "© OpenStreetMap contributors", 86400, 604800);
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
    void izelmanFacilitiesHiddenWhenPublicationDisabledAndNeverExposeOccupancy() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var izelman = new MunicipalFacilityRepository.Facility(
                id, "İZELMAN lot", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET, "", 38.4, 27.1,
                80, true, false, "IZELMAN A.S.",
                "Izmir Metropolitan Municipality / IZELMAN A.S.", 15552000, 63072000);
        when(facilities.nearby(38.4, 27.1, 1000, 10)).thenReturn(List.of(izelman));
        when(facilities.findById(id)).thenReturn(Optional.of(izelman));
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

    private static MunicipalFacilityQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            MunicipalSourceProperties municipal,
            IzelmanProperties izelman) {
        return new MunicipalFacilityQueryService(
                facilities, snapshots, municipal, izelman,
                Clock.fixed(Instant.parse("2026-07-30T06:00:00Z"), ZoneOffset.UTC));
    }
}
