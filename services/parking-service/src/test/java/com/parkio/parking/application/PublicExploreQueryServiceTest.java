package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.ParkingSpotRepository;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicExploreQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void defaultDiscoveryUsesFixedIzmirCenterRadiusAndLimitSix() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var spots = mock(ParkingSpotRepository.class);
        when(facilities.countPublicExploreIzumNearby(38.4237, 27.1428, 5_000)).thenReturn(13L);
        when(facilities.publicExploreIzumNearby(38.4237, 27.1428, 5_000, 6))
                .thenReturn(IntStream.range(0, 6).mapToObj(i -> facility(UUID.randomUUID(), 38.4237, 27.1428))
                        .toList());
        when(spots.countNearbyVisible(38.4237, 27.1428, 5_000)).thenReturn(23L);

        var result = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        assertThat(result.facilities()).hasSize(6);
        assertThat(result.municipalTotalInScope()).isEqualTo(13L);
        assertThat(result.municipalHiddenCount()).isEqualTo(7L);
        assertThat(result.communitySpotCountInScope()).isEqualTo(23);
        verify(facilities).publicExploreIzumNearby(38.4237, 27.1428, 5_000, 6);
        verify(facilities, never()).nearby(anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void locationQueryChangesMunicipalProximityScope() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var spots = mock(ParkingSpotRepository.class);
        when(facilities.countPublicExploreIzumNearby(38.45, 27.15, 1_500)).thenReturn(4L);
        when(facilities.publicExploreIzumNearby(38.45, 27.15, 1_500, 6))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.45, 27.15)));
        when(spots.countNearbyVisible(38.45, 27.15, 1_500)).thenReturn(0L);

        var result = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(38.45, 27.15, 1_500, null));

        assertThat(result.facilities()).hasSize(1);
        assertThat(result.municipalTotalInScope()).isEqualTo(4L);
        assertThat(result.municipalHiddenCount()).isEqualTo(3L);
        verify(facilities).publicExploreIzumNearby(38.45, 27.15, 1_500, 6);
    }

    @Test
    void hiddenMunicipalRowsAreNeverReturnedBeyondVisibleCap() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var spots = mock(ParkingSpotRepository.class);
        List<MunicipalFacilityRepository.Facility> visible = IntStream.range(0, 6)
                .mapToObj(i -> facility(UUID.fromString("00000000-0000-0000-0000-00000000000" + i), 38.4237, 27.1428))
                .toList();
        when(facilities.countPublicExploreIzumNearby(38.4237, 27.1428, 5_000)).thenReturn(13L);
        when(facilities.publicExploreIzumNearby(38.4237, 27.1428, 5_000, 6)).thenReturn(visible);
        when(spots.countNearbyVisible(38.4237, 27.1428, 5_000)).thenReturn(3L);

        var result = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, 6));

        assertThat(result.facilities()).hasSize(6);
        assertThat(result.facilities()).extracting(PublicExploreQueryService.FacilityView::id)
                .containsExactlyElementsOf(visible.stream().map(MunicipalFacilityRepository.Facility::id).toList());
        assertThat(result.municipalHiddenCount()).isEqualTo(7L);
    }

    @Test
    void publishesAvailabilityOnlyForCanonicalLiveOrAgingStates() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var spots = mock(ParkingSpotRepository.class);
        when(facilities.countPublicExploreIzumNearby(38.4237, 27.1428, 5_000)).thenReturn(1L);
        when(facilities.publicExploreIzumNearby(38.4237, 27.1428, 5_000, 6))
                .thenReturn(List.of(facility(id, 38.4237, 27.1428)));
        when(spots.countNearbyVisible(38.4237, 27.1428, 5_000)).thenReturn(0L);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, true)));
        var live = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(live.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(live.availableSpaces()).isEqualTo(90);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(40), 30L, true)));
        var aging = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(aging.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.AGING);
        assertThat(aging.availableSpaces()).isEqualTo(90);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(60), 60L, true)));
        var stale = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(stale.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(stale.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, false)));
        var invalid = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(invalid.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.INVALID);
        assertThat(invalid.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.empty());
        var unavailable = service(facilities, snapshots, spots, enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(unavailable.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(unavailable.availableSpaces()).isNull();
    }

    @Test
    void emptySourceAllowlistReturnsNoDataAndDoesNotQueryRepository() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var spots = mock(ParkingSpotRepository.class);
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);

        var result = service(facilities, snapshots, spots, properties)
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        assertThat(result.facilities()).isEmpty();
        assertThat(result.municipalTotalInScope()).isZero();
        assertThat(result.communitySpotCountInScope()).isNull();
        verify(facilities, never()).publicExploreIzumNearby(anyDouble(), anyDouble(), anyInt(), anyInt());
        verify(spots, never()).countNearbyVisible(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void communityPrivacyThresholdSuppressesBelowThree() {
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(0)).isNull();
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(1)).isNull();
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(2)).isNull();
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(3)).isEqualTo(3);
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(4)).isEqualTo(4);
        assertThat(PublicExploreQueryService.suppressCommunityBelowThreshold(10)).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7, 20, 100})
    void rejectsLimitOutsideServerCap(int limit) {
        assertThatThrownBy(() -> service(mock(MunicipalFacilityRepository.class),
                mock(MunicipalOccupancySnapshotRepository.class),
                mock(ParkingSpotRepository.class),
                enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, limit)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 5_001, 50_000})
    void rejectsRadiusOutsideServerCap(int radius) {
        assertThatThrownBy(() -> service(mock(MunicipalFacilityRepository.class),
                mock(MunicipalOccupancySnapshotRepository.class),
                mock(ParkingSpotRepository.class),
                enabledIzum())
                .discover(new PublicExploreQueryService.DiscoveryQuery(38.42, 27.14, radius, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radiusMeters");
    }

    @Test
    void rejectsPartialCoordinatesAndOutOfRangeValues() {
        var svc = service(mock(MunicipalFacilityRepository.class),
                mock(MunicipalOccupancySnapshotRepository.class),
                mock(ParkingSpotRepository.class),
                enabledIzum());
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(38.42, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(null, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(91.0, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(38.42, 181.0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(
                        new PublicExploreQueryService.DiscoveryQuery(Double.NaN, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(
                        new PublicExploreQueryService.DiscoveryQuery(38.42, Double.POSITIVE_INFINITY, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MunicipalFacilityRepository.Facility facility(UUID id, double lat, double lng) {
        return new MunicipalFacilityRepository.Facility(
                id, "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", lat, lng, 120, true, true,
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
            ParkingSpotRepository spots,
            PublicExploreProperties properties) {
        return new PublicExploreQueryService(
                facilities, snapshots, spots, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
