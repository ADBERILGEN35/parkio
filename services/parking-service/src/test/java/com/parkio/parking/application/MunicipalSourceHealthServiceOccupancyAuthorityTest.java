package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MunicipalSourceHealthServiceOccupancyAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-03T15:00:00Z");
    private static final UUID OSM_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IZUM_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private MunicipalDataSourceRepository sources;
    private MunicipalSourceSyncRunRepository runs;
    private MunicipalOccupancySnapshotRepository occupancy;
    private MunicipalSourceHealthService service;

    @BeforeEach
    void setUp() {
        sources = mock(MunicipalDataSourceRepository.class);
        runs = mock(MunicipalSourceSyncRunRepository.class);
        occupancy = mock(MunicipalOccupancySnapshotRepository.class);
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.setEnabled(true);
        properties.getOps().setSourceModeSlaEnabled(true);
        properties.getOsm().setOperatingMode(MunicipalSourceOperatingMode.OPERATOR_IMPORTED);
        properties.getIzum().setOperatingMode(MunicipalSourceOperatingMode.SCHEDULED);
        properties.getIzum().setEnabled(true);
        properties.getIzum().setSchedulerEnabled(true);
        MunicipalSourceSlaPolicy.Thresholds thresholds =
                new MunicipalSourceSlaPolicy.Thresholds(3, 5, 600, 1800, 900, 900);
        service = new MunicipalSourceHealthService(
                sources,
                runs,
                occupancy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                thresholds,
                properties,
                true,
                true,
                true,
                MunicipalSourceIdentity.IZUM);
        when(runs.findRecentCompleted(any(), anyInt())).thenReturn(List.of());
        when(runs.countFailuresSince(any(), any())).thenReturn(0);
        when(runs.countStaleRunning(any(), any())).thenReturn(0);
    }

    @Test
    void osmHealthySuccessWithoutOccupancyIsUnavailable() {
        Instant success = NOW.minusSeconds(7200);
        when(sources.findBySourceKey(MunicipalSourceIdentity.OSM))
                .thenReturn(Optional.of(source(OSM_ID, MunicipalSourceIdentity.OSM, 86400, 604800, success)));
        when(runs.findRecentCompleted(eq(OSM_ID), anyInt())).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "SUCCESS", null, success.minusSeconds(10), success)));
        when(occupancy.latestForSource(OSM_ID)).thenReturn(Optional.empty());

        MunicipalSourceHealthService.Snapshot snapshot =
                service.snapshot(MunicipalSourceIdentity.OSM, true, false);

        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.HEALTHY);
        assertThat(snapshot.occupancyFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
        assertThat(snapshot.secondsSinceSuccess()).isGreaterThanOrEqualTo(7200);
    }

    @Test
    void osmDoesNotBecomeLiveFromImportSuccessTimestamp() {
        Instant success = NOW.minusSeconds(30);
        when(sources.findBySourceKey(MunicipalSourceIdentity.OSM))
                .thenReturn(Optional.of(source(OSM_ID, MunicipalSourceIdentity.OSM, 300, 900, success)));
        when(runs.findRecentCompleted(eq(OSM_ID), anyInt())).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "SUCCESS", null, success.minusSeconds(5), success)));
        // Even if a rogue occupancy row existed, OSM authority must stay UNAVAILABLE.
        when(occupancy.latestForSource(OSM_ID)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(10, 2, 8, success, 0L, true)));

        assertThat(service.snapshot(MunicipalSourceIdentity.OSM, true, false).occupancyFreshness())
                .isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    @Test
    void izumUsesLatestOccupancyObservationNotSyncSuccess() {
        Instant oldSync = NOW.minusSeconds(7200);
        Instant freshObs = NOW.minusSeconds(20);
        when(sources.findBySourceKey(MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(source(IZUM_ID, MunicipalSourceIdentity.IZUM, 300, 900, oldSync)));
        when(runs.findRecentCompleted(eq(IZUM_ID), anyInt())).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "SUCCESS", null, oldSync.minusSeconds(5), oldSync)));
        when(occupancy.latestForSource(IZUM_ID)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(40, 10, 30, freshObs, 0L, true)));

        MunicipalSourceHealthService.Snapshot snapshot = service.izumSnapshot();
        assertThat(snapshot.operationalState()).isEqualTo(MunicipalSourceOperationalState.CRITICAL);
        assertThat(snapshot.occupancyFreshness()).isEqualTo(MunicipalOccupancyFreshness.LIVE);
    }

    @Test
    void izumMissingOccupancyIsUnavailableWhileOpsMayBeHealthy() {
        Instant success = NOW.minusSeconds(30);
        when(sources.findBySourceKey(MunicipalSourceIdentity.IZUM))
                .thenReturn(Optional.of(source(IZUM_ID, MunicipalSourceIdentity.IZUM, 300, 900, success)));
        when(runs.findRecentCompleted(eq(IZUM_ID), anyInt())).thenReturn(List.of(
                new MunicipalSourceSyncRunRepository.CompletedRunView(
                        "SUCCESS", null, success.minusSeconds(5), success)));
        when(occupancy.latestForSource(IZUM_ID)).thenReturn(Optional.empty());

        MunicipalSourceHealthService.Snapshot snapshot = service.izumSnapshot();
        assertThat(snapshot.operationalState()).isIn(
                MunicipalSourceOperationalState.HEALTHY, MunicipalSourceOperationalState.RECOVERING);
        assertThat(snapshot.occupancyFreshness()).isEqualTo(MunicipalOccupancyFreshness.UNAVAILABLE);
    }

    private static MunicipalDataSourceRepository.Source source(
            UUID id, String key, long aging, long stale, Instant lastSuccess) {
        return new MunicipalDataSourceRepository.Source(
                id, key, "publisher", "attribution", aging, stale, lastSuccess, true);
    }
}
