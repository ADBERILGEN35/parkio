package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.RoadsideDiscoveryQueryPort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
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
    private static final Set<String> IZUM_KEYS = Set.of(MunicipalSourceIdentity.IZUM);
    private static final Set<String> BOTH_KEYS =
            Set.of(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.ISPARK);

    @Test
    void defaultDiscoveryUsesFixedIzmirCenterRadiusAndLimitSix() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(13L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(IntStream.range(0, 6)
                        .mapToObj(i -> facility(UUID.randomUUID(), 38.4237, 27.1428, MunicipalSourceIdentity.IZUM))
                        .toList());

        var result = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        assertThat(result.facilities()).hasSize(6);
        assertThat(result.municipalTotalInScope()).isEqualTo(13L);
        assertThat(result.municipalHiddenCount()).isEqualTo(7L);
        assertThat(result.communitySpotCountInScope()).isNull();
        verify(facilities).publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS);
        verify(facilities, never()).nearby(anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void locationQueryChangesMunicipalProximityScope() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(38.45, 27.15, 1_500, IZUM_KEYS)).thenReturn(4L);
        when(facilities.publicExploreNearby(38.45, 27.15, 1_500, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.45, 27.15, MunicipalSourceIdentity.IZUM)));

        var result = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(38.45, 27.15, 1_500, null));

        assertThat(result.facilities()).hasSize(1);
        assertThat(result.municipalTotalInScope()).isEqualTo(4L);
        assertThat(result.municipalHiddenCount()).isEqualTo(3L);
        verify(facilities).publicExploreNearby(38.45, 27.15, 1_500, 6, IZUM_KEYS);
    }

    @Test
    void kadikoyOriginUsesSuppliedCoordinatesNotIzmirCenter() {
        double kadikoyLat = 40.9901;
        double kadikoyLng = 29.0290;
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        UUID id = UUID.randomUUID();
        when(facilities.countPublicExploreNearby(kadikoyLat, kadikoyLng, 5_000, BOTH_KEYS)).thenReturn(2L);
        when(facilities.publicExploreNearby(kadikoyLat, kadikoyLng, 5_000, 6, BOTH_KEYS))
                .thenReturn(List.of(facility(id, kadikoyLat, kadikoyLng, MunicipalSourceIdentity.ISPARK)));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.empty());

        var result = service(facilities, snapshots, enabledFamilies("IZUM", "ISPARK"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(kadikoyLat, kadikoyLng, null, null));

        assertThat(result.facilities()).hasSize(1);
        assertThat(result.facilities().getFirst().sourceLabel())
                .isEqualTo(ParkingProviderCatalog.ISPARK_DISPLAY_NAME);
        assertThat(result.facilities().getFirst().attribution())
                .isEqualTo(ParkingProviderCatalog.ISPARK_ATTRIBUTION);
        verify(facilities).publicExploreNearby(kadikoyLat, kadikoyLng, 5_000, 6, BOTH_KEYS);
        verify(facilities, never()).publicExploreNearby(
                eq(38.4237), eq(27.1428), anyInt(), anyInt(), any());
    }

    @Test
    void mixedProvidersRespectGlobalLimitAndRowSpecificAttribution() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        UUID izumId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID isparkId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(facilities.countPublicExploreNearby(40.99, 29.03, 5_000, BOTH_KEYS)).thenReturn(9L);
        when(facilities.publicExploreNearby(40.99, 29.03, 5_000, 6, BOTH_KEYS))
                .thenReturn(List.of(
                        facility(izumId, 40.9902, 29.0291, MunicipalSourceIdentity.IZUM),
                        facility(isparkId, 40.9903, 29.0292, MunicipalSourceIdentity.ISPARK,
                                "{\"isOpen\":1}")));
        when(snapshots.latestForFacilityAndSourceKey(izumId, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        100, 20, 50, NOW.minusSeconds(5), 5L, true)));
        when(snapshots.latestForFacilityAndSourceKey(isparkId, MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        200, 40, 80, NOW.minusSeconds(5), 5L, true)));

        var result = service(facilities, snapshots, enabledFamilies("IZUM", "ISPARK"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(40.99, 29.03, null, null));

        assertThat(result.facilities()).hasSize(2);
        assertThat(result.municipalTotalInScope()).isEqualTo(9L);
        assertThat(result.municipalHiddenCount()).isEqualTo(7L);
        var byId = result.facilities().stream()
                .collect(java.util.stream.Collectors.toMap(
                        PublicExploreQueryService.FacilityView::id,
                        java.util.function.Function.identity()));
        assertThat(byId.get(izumId).sourceLabel()).isEqualTo(ParkingProviderCatalog.IZUM_DISPLAY_NAME);
        assertThat(byId.get(izumId).availableSpaces()).isEqualTo(50);
        assertThat(byId.get(isparkId).sourceLabel()).isEqualTo(ParkingProviderCatalog.ISPARK_DISPLAY_NAME);
        assertThat(byId.get(isparkId).availableSpaces()).isEqualTo(80);
        verify(snapshots).latestForFacilityAndSourceKey(izumId, MunicipalSourceIdentity.IZUM);
        verify(snapshots).latestForFacilityAndSourceKey(isparkId, MunicipalSourceIdentity.ISPARK);
        verify(snapshots, never()).latestForFacilityAndSourceKey(izumId, MunicipalSourceIdentity.ISPARK);
        verify(snapshots, never()).latestForFacilityAndSourceKey(isparkId, MunicipalSourceIdentity.IZUM);
    }

    @Test
    void ingestionEnabledDoesNotImplyPublicationWithoutAllowlist() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);
        properties.setAllowedSourceFamilies(List.of("IZUM"));

        service(facilities, snapshots, properties)
                .discover(new PublicExploreQueryService.DiscoveryQuery(40.99, 29.03, null, null));

        verify(facilities).publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), eq(IZUM_KEYS));
        verify(facilities, never())
                .publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), eq(BOTH_KEYS));
    }

    @Test
    void hiddenMunicipalRowsAreNeverReturnedBeyondVisibleCap() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        List<MunicipalFacilityRepository.Facility> visible = IntStream.range(0, 6)
                .mapToObj(i -> facility(
                        UUID.fromString("00000000-0000-0000-0000-00000000000" + i),
                        38.4237,
                        27.1428,
                        MunicipalSourceIdentity.IZUM))
                .toList();
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(13L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS)).thenReturn(visible);

        var result = service(facilities, snapshots, enabledFamilies("izum"))
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
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(1L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(id, 38.4237, 27.1428, MunicipalSourceIdentity.IZUM)));

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, true)));
        var live = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(live.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(live.availableSpaces()).isEqualTo(90);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(40), 30L, true)));
        var aging = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(aging.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.AGING);
        assertThat(aging.availableSpaces()).isEqualTo(90);

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(60), 60L, true)));
        var stale = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(stale.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(stale.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, false)));
        var invalid = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(invalid.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.INVALID);
        assertThat(invalid.availableSpaces()).isNull();

        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.empty());
        var unavailable = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();
        assertThat(unavailable.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(unavailable.availableSpaces()).isNull();
    }

    @Test
    void closedIsparkWithPositiveEmptyCapacityPublishesUnavailableAndOmitsSpaces() {
        UUID id = UUID.fromString("81279bd3-5c60-42a1-81bc-8255e22a1a48");
        var view = discoverIspark(
                facility(id, 40.9712, 28.7185, MunicipalSourceIdentity.ISPARK,
                        "{\"isOpen\":0,\"district\":\"AVCILAR\",\"workHours\":\"Kapalı\"}"),
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, NOW.minusSeconds(5), 5L, true));

        assertThat(view.id()).isEqualTo(id);
        assertThat(view.displayName()).isEqualTo("Konak Otopark");
        assertThat(view.capacityTotal()).isEqualTo(270);
        assertThat(view.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.sourceLabel()).isEqualTo(ParkingProviderCatalog.ISPARK_DISPLAY_NAME);
    }

    @Test
    void openIsparkPreservesPositiveAvailability() {
        UUID id = UUID.randomUUID();
        var view = discoverIspark(
                facility(id, 40.99, 29.03, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":1}"),
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 40, 80, NOW.minusSeconds(5), 5L, true));

        assertThat(view.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(view.availableSpaces()).isEqualTo(80);
    }

    @Test
    void openIsparkPreservesZeroAvailability() {
        UUID id = UUID.randomUUID();
        var view = discoverIspark(
                facility(id, 40.99, 29.03, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":1}"),
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        50, 50, 0, NOW.minusSeconds(5), 5L, true));

        assertThat(view.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(view.availableSpaces()).isZero();
    }

    @Test
    void missingNullAndUnrecognizedIsparkOpenStatusAreNotTreatedAsOpen() {
        UUID missingId = UUID.randomUUID();
        UUID nullId = UUID.randomUUID();
        UUID unrecognizedId = UUID.randomUUID();
        var live = new MunicipalOccupancySnapshotRepository.Snapshot(
                80, 39, 41, NOW.minusSeconds(5), 5L, true);

        var missing = discoverIspark(
                facility(missingId, 40.97, 28.71, MunicipalSourceIdentity.ISPARK, null), live);
        var nullStatus = discoverIspark(
                facility(nullId, 40.97, 28.71, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":null}"), live);
        var unrecognized = discoverIspark(
                facility(unrecognizedId, 40.97, 28.71, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":\"maybe\"}"),
                live);

        assertThat(missing.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(missing.availableSpaces()).isNull();
        assertThat(nullStatus.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(nullStatus.availableSpaces()).isNull();
        assertThat(unrecognized.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(unrecognized.availableSpaces()).isNull();
    }

    @Test
    void closedIsparkFacilityRemainsDiscoverable() {
        UUID id = UUID.fromString("d1b866b1-fe41-4ac3-b91b-601a07b57b2a");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        Set<String> isparkKeys = Set.of(MunicipalSourceIdentity.ISPARK);
        when(facilities.countPublicExploreNearby(40.9723, 28.714, 5_000, isparkKeys)).thenReturn(1L);
        when(facilities.publicExploreNearby(40.9723, 28.714, 5_000, 6, isparkKeys))
                .thenReturn(List.of(facility(
                        id, 40.9723, 28.714, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":0}")));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        80, 39, 41, NOW.minusSeconds(5), 5L, true)));

        var result = service(facilities, snapshots, enabledFamilies("ISPARK"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(40.9723, 28.714, 5_000, 6));

        assertThat(result.facilities()).extracting(PublicExploreQueryService.FacilityView::id)
                .containsExactly(id);
        assertThat(result.municipalTotalInScope()).isEqualTo(1L);
        assertThat(result.municipalHiddenCount()).isZero();
        assertThat(result.facilities().getFirst().availabilityFreshness())
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(result.facilities().getFirst().availableSpaces()).isNull();
    }

    @Test
    void isparkStaleSuppressionRemainsIntactForOpenFacilities() {
        UUID id = UUID.randomUUID();
        var view = discoverIspark(
                facility(id, 40.99, 29.03, MunicipalSourceIdentity.ISPARK, "{\"isOpen\":1}"),
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(60), 60L, true));

        assertThat(view.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.STALE);
        assertThat(view.availableSpaces()).isNull();
    }

    @Test
    void izumOccupancyIsUnchangedWhenIsparkMetadataWouldBeClosed() {
        UUID id = UUID.randomUUID();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(1L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(
                        id, 38.4237, 27.1428, MunicipalSourceIdentity.IZUM, "{\"isOpen\":0}")));
        when(snapshots.latestForFacilityAndSourceKey(id, MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 30, 90, NOW.minusSeconds(10), 10L, true)));

        var view = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null))
                .facilities()
                .getFirst();

        assertThat(view.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(view.availableSpaces()).isEqualTo(90);
    }

    @Test
    void emptySourceAllowlistReturnsNoDataAndDoesNotQueryRepository() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);

        var result = service(facilities, snapshots, properties)
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        assertThat(result.facilities()).isEmpty();
        assertThat(result.municipalTotalInScope()).isZero();
        assertThat(result.communitySpotCountInScope()).isNull();
        verify(facilities, never()).publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), any());
    }

    @Test
    void communityAggregateAlwaysWithheldOnPublicDiscover() {
        assertThat(PublicExploreQueryService.withholdCommunityAggregate()).isNull();
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(13L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.4237, 27.1428, MunicipalSourceIdentity.IZUM)));
        var result = service(facilities, snapshots, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));
        assertThat(result.communitySpotCountInScope()).isNull();
        assertThat(result.facilities()).hasSize(1);
        assertThat(result.municipalTotalInScope()).isEqualTo(13L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, -1})
    void rejectsInvalidLimit(int limit) {
        assertThatThrownBy(() -> service(
                        mock(MunicipalFacilityRepository.class),
                        mock(MunicipalOccupancySnapshotRepository.class),
                        enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, limit)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5_001, -10})
    void rejectsInvalidRadius(int radius) {
        assertThatThrownBy(() -> service(
                        mock(MunicipalFacilityRepository.class),
                        mock(MunicipalOccupancySnapshotRepository.class),
                        enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(38.42, 27.14, radius, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPartialOrOutOfRangeCoordinates() {
        var svc = service(
                mock(MunicipalFacilityRepository.class),
                mock(MunicipalOccupancySnapshotRepository.class),
                enabledFamilies("izum"));
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(38.42, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(null, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(91.0, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.discover(new PublicExploreQueryService.DiscoveryQuery(38.42, 181.0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        svc.discover(new PublicExploreQueryService.DiscoveryQuery(Double.NaN, 27.14, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        svc.discover(new PublicExploreQueryService.DiscoveryQuery(
                                38.42, Double.POSITIVE_INFINITY, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, double lat, double lng, String sourceKey) {
        return facility(id, lat, lng, sourceKey, null);
    }

    private static MunicipalFacilityRepository.Facility facility(
            UUID id, double lat, double lng, String sourceKey, String isparkSourceMetadataJson) {
        return new MunicipalFacilityRepository.Facility(
                id, "Konak Otopark", "IZELMAN A.S.", MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir", lat, lng, 120, true, true,
                "ignored", "ignored", 60, 120, sourceKey,
                Set.of(sourceKey), MunicipalAccessClassification.PUBLIC, isparkSourceMetadataJson);
    }

    private static PublicExploreQueryService.FacilityView discoverIspark(
            MunicipalFacilityRepository.Facility facility,
            MunicipalOccupancySnapshotRepository.Snapshot snapshot) {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        Set<String> isparkKeys = Set.of(MunicipalSourceIdentity.ISPARK);
        when(facilities.countPublicExploreNearby(facility.latitude(), facility.longitude(), 5_000, isparkKeys))
                .thenReturn(1L);
        when(facilities.publicExploreNearby(facility.latitude(), facility.longitude(), 5_000, 6, isparkKeys))
                .thenReturn(List.of(facility));
        when(snapshots.latestForFacilityAndSourceKey(facility.id(), MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(snapshot));
        return service(facilities, snapshots, enabledFamilies("ISPARK"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(
                        facility.latitude(), facility.longitude(), 5_000, 6))
                .facilities()
                .getFirst();
    }

    private static PublicExploreProperties enabledFamilies(String... families) {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);
        properties.setAllowedSourceFamilies(List.of(families));
        return properties;
    }

    @Test
    void izelmanAllowlistMergesPublishedRoadsideWithUnknownAccessAndUnavailableOccupancy() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var roadside = mock(RoadsideDiscoveryQueryPort.class);
        Set<String> izelmanKeys = Set.of(
                "izelman-open-parking-facilities",
                "izelman-closed-parking-facilities",
                "izelman-barrier-parking-facilities");
        UUID roadsideId = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
        when(facilities.countPublicExploreNearby(38.42, 27.14, 5_000, izelmanKeys)).thenReturn(2L);
        when(facilities.publicExploreNearby(38.42, 27.14, 5_000, 6, izelmanKeys))
                .thenReturn(List.of(facility(
                        UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                        38.421,
                        27.141,
                        "izelman-open-parking-facilities")));
        when(roadside.countNearby(38.42, 27.14, 5_000)).thenReturn(48L);
        when(roadside.nearby(38.42, 27.14, 5_000, 6))
                .thenReturn(List.of(new RoadsideDiscoveryQueryPort.RoadsideSegment(
                        roadsideId,
                        "Alsancak roadside sample",
                        "Alsancak",
                        38.4201,
                        27.1401,
                        12,
                        NOW)));

        var result = service(facilities, snapshots, roadside, enabledFamilies("IZELMAN"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(38.42, 27.14, 5_000, null));

        assertThat(result.municipalTotalInScope()).isEqualTo(50L);
        assertThat(result.facilities()).extracting(PublicExploreQueryService.FacilityView::id)
                .contains(roadsideId);
        var roadsideView = result.facilities().stream()
                .filter(view -> view.id().equals(roadsideId))
                .findFirst()
                .orElseThrow();
        assertThat(roadsideView.sourceLabel())
                .isEqualTo(PublicExploreQueryService.IZELMAN_ROADSIDE_SOURCE_LABEL);
        assertThat(roadsideView.attribution())
                .isEqualTo(PublicExploreQueryService.IZELMAN_ROADSIDE_ATTRIBUTION);
        assertThat(roadsideView.facilityType()).isEqualTo(MunicipalFacilityType.ON_STREET);
        assertThat(roadsideView.accessClassification()).isEqualTo(MunicipalAccessClassification.UNKNOWN);
        assertThat(roadsideView.availabilityFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(roadsideView.availableSpaces()).isNull();
        verify(roadside).nearby(38.42, 27.14, 5_000, 6);
    }

    @Test
    void izumOnlyAllowlistDoesNotQueryRoadside() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var roadside = mock(RoadsideDiscoveryQueryPort.class);
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(1L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.4237, 27.1428, MunicipalSourceIdentity.IZUM)));

        service(facilities, snapshots, roadside, enabledFamilies("izum"))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        verify(roadside, never()).countNearby(anyDouble(), anyDouble(), anyInt());
        verify(roadside, never()).nearby(anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    private static PublicExploreQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            PublicExploreProperties properties) {
        return service(facilities, snapshots, mock(RoadsideDiscoveryQueryPort.class), properties);
    }

    private static PublicExploreQueryService service(
            MunicipalFacilityRepository facilities,
            MunicipalOccupancySnapshotRepository snapshots,
            RoadsideDiscoveryQueryPort roadside,
            PublicExploreProperties properties) {
        return new PublicExploreQueryService(
                facilities, snapshots, roadside, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
