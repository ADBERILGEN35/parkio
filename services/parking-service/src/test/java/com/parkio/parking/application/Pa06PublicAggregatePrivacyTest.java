package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PA-06 / G06: synthetic differencing against the former threshold publisher, then
 * proof that anonymous Explore no longer publishes community counts under free geometry.
 */
class Pa06PublicAggregatePrivacyTest {
    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
    private static final Set<String> IZUM_KEYS = Set.of(MunicipalSourceIdentity.IZUM);

    /**
     * Former public publisher (k=3 exact count). Kept only to document the baseline
     * disclosure; production code no longer uses this path.
     */
    static Integer legacyPublishExactAboveThreshold(long rawCount) {
        if (rawCount < 3) {
            return null;
        }
        if (rawCount > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) rawCount;
    }

    /** Synthetic local-meter disk membership (audit fixture geometry). */
    static long countInDisk(List<double[]> pointsXyMeters, double cx, double cy, double radiusM) {
        long n = 0;
        for (double[] p : pointsXyMeters) {
            double dx = p[0] - cx;
            double dy = p[1] - cy;
            if (dx * dx + dy * dy <= radiusM * radiusM) {
                n++;
            }
        }
        return n;
    }

    @Test
    void baselineLegacyPublisherAllowsRadiusDifferencing() {
        // Three anchors inside r=99; one extra in the 99–101 annulus.
        List<double[]> points = List.of(
                new double[] {0, 0},
                new double[] {10, 0},
                new double[] {0, 10},
                new double[] {100, 0});
        Integer at99 = legacyPublishExactAboveThreshold(countInDisk(points, 0, 0, 99));
        Integer at101 = legacyPublishExactAboveThreshold(countInDisk(points, 0, 0, 101));
        assertThat(at99).isEqualTo(3);
        assertThat(at101).isEqualTo(4);
        assertThat(at101 - at99).isEqualTo(1);
    }

    @Test
    void baselineLegacyPublisherAllowsMovingCenterDifferencing() {
        List<double[]> points = List.of(
                new double[] {0, 0},
                new double[] {10, 0},
                new double[] {-10, 0},
                new double[] {100, 0});
        Integer left = legacyPublishExactAboveThreshold(countInDisk(points, -1, 0, 100));
        Integer right = legacyPublishExactAboveThreshold(countInDisk(points, 1, 0, 100));
        assertThat(left).isEqualTo(3);
        assertThat(right).isEqualTo(4);
    }

    @Test
    void baselineSparseThresholdCrossingIsObservable() {
        assertThat(legacyPublishExactAboveThreshold(2)).isNull();
        assertThat(legacyPublishExactAboveThreshold(3)).isEqualTo(3);
    }

    @Test
    void correctedDiscoverWithholdsCommunityAcrossRadiusAndCenterVariants() {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(5L);
        when(facilities.publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.42, 27.14)));

        PublicExploreQueryService svc = new PublicExploreQueryService(
                facilities, snapshots, enabledIzum(), Clock.fixed(NOW, ZoneOffset.UTC));

        List<PublicExploreQueryService.DiscoveryQuery> queries = List.of(
                new PublicExploreQueryService.DiscoveryQuery(38.42, 27.14, 99, null),
                new PublicExploreQueryService.DiscoveryQuery(38.42, 27.14, 101, null),
                new PublicExploreQueryService.DiscoveryQuery(38.42001, 27.14, 100, null),
                new PublicExploreQueryService.DiscoveryQuery(38.41999, 27.14, 100, null),
                new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        List<Integer> published = new ArrayList<>();
        for (var q : queries) {
            var result = svc.discover(q);
            published.add(result.communitySpotCountInScope());
            assertThat(result.communitySpotCountInScope()).isNull();
            assertThat(result.municipalTotalInScope()).isEqualTo(5L);
            assertThat(result.facilities()).isNotEmpty();
        }
        // No complementary total: every overlapping query yields the same withheld signal.
        assertThat(published.stream().distinct()).containsExactly((Integer) null);
    }

    @Test
    void correctedDiscoverNeverQueriesCommunitySpotRepository() {
        // Structural: ParkingSpotRepository is no longer a dependency of public discover.
        // Municipal path still executes.
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(38.4237, 27.1428, 5_000, IZUM_KEYS)).thenReturn(2L);
        when(facilities.publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS))
                .thenReturn(List.of(facility(UUID.randomUUID(), 38.4237, 27.1428)));

        var result = new PublicExploreQueryService(
                        facilities, snapshots, enabledIzum(), Clock.fixed(NOW, ZoneOffset.UTC))
                .discover(new PublicExploreQueryService.DiscoveryQuery(null, null, null, null));

        assertThat(result.communitySpotCountInScope()).isNull();
        verify(facilities).publicExploreNearby(38.4237, 27.1428, 5_000, 6, IZUM_KEYS);
        verify(facilities, never()).nearby(anyDouble(), anyDouble(), anyInt(), anyInt());
    }

    private static MunicipalFacilityRepository.Facility facility(UUID id, double lat, double lng) {
        return new MunicipalFacilityRepository.Facility(
                id,
                "Konak Otopark",
                "IZELMAN A.S.",
                MunicipalFacilityType.OFF_STREET,
                "Konak, Izmir",
                lat,
                lng,
                120,
                true,
                true,
                "ignored",
                "ignored",
                60,
                120,
                MunicipalSourceIdentity.IZUM,
                Set.of(MunicipalSourceIdentity.IZUM),
                MunicipalAccessClassification.PUBLIC);
    }

    private static PublicExploreProperties enabledIzum() {
        PublicExploreProperties properties = new PublicExploreProperties();
        properties.setEnabled(true);
        properties.setAllowedSourceFamilies(List.of("izum"));
        return properties;
    }
}
