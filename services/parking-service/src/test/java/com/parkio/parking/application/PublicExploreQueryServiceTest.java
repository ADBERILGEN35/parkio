package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicExploreQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void usesOnlyTheFixedBoundedRepositoryQuery() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        PublicExploreProperties properties = enabledIzum();
        when(facilities.publicExploreIzumNearby(38.4237, 27.1428, 5_000, 20))
                .thenReturn(List.of(facility(UUID.randomUUID())));

        var result = service(facilities, snapshots, properties).list();

        assertThat(result).hasSize(1);
        verify(facilities).publicExploreIzumNearby(38.4237, 27.1428, 5_000, 20);
        verify(facilities, never()).nearby(38.4237, 27.1428, 5_000, 20);
    }

    @Test
    void publishesAvailabilityOnlyForCanonicalLiveOrAgingStates() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.findPublicExploreIzumById(id, 38.4237, 27.1428, 5_000))
                .thenReturn(Optional.of(facility(id)));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, true)));

        var live = service(facilities, snapshots, enabledIzum()).findById(id).orElseThrow();

        assertThat(live.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(live.availableSpaces()).isEqualTo(90);
        assertThat(live.dataUpdatedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(live.sourceLabel()).contains("IZUM");
        assertThat(live.attribution()).contains("CC BY 4.0");

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(40), 30L, true)));
        var aging = service(facilities, snapshots, enabledIzum()).findById(id).orElseThrow();
        assertThat(aging.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.AGING);
        assertThat(aging.availableSpaces()).isEqualTo(90);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(60), 60L, true)));
        var stale = service(facilities, snapshots, enabledIzum()).findById(id).orElseThrow();
        assertThat(stale.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(stale.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, false)));
        var invalid = service(facilities, snapshots, enabledIzum()).findById(id).orElseThrow();
        assertThat(invalid.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.INVALID);
        assertThat(invalid.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.empty());
        var unavailable = service(facilities, snapshots, enabledIzum()).findById(id).orElseThrow();
        assertThat(unavailable.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(unavailable.availableSpaces()).isNull();
    }

    @Test
    void emptySourceAllowlistReturnsNoDataAndDoesNotQueryRepository() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);

        var service = service(facilities, snapshots, properties);

        assertThat(service.list()).isEmpty();
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
        verify(facilities, never()).publicExploreIzumNearby(38.4237, 27.1428, 5_000, 20);
    }

    private static MunicipalFacilityRepository.Facility facility(UUID id) {
        return new MunicipalFacilityRepository.Facility(
                id, "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", 38.4237, 27.1428, 120, true, true,
                "ignored", "ignored", 60, 120, MunicipalSourceIdentity.IZUM,
                Set.of(MunicipalSourceIdentity.IZUM), MunicipalAccessClassification.PUBLIC);
    }

    private static PublicExploreProperties enabledIzum() {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);
        properties.setAllowedSourceFamilies(List.of("izum"));
        return properties;
    }

    private static PublicExploreQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            PublicExploreProperties properties) {
        return new PublicExploreQueryService(
                facilities, snapshots, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
