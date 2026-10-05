package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.externalsource.MunicipalFeedChange;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MunicipalSourceMetricsTest {

    @Test
    void recordsBoundedLabelsAndEmitsRecoveryWithoutExceptionText() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
        MunicipalSourceProperties properties = new MunicipalSourceProperties();

        MunicipalSourceSlaPolicy.Evaluation failing = evaluation(3, false, MunicipalSourceOperationalState.DEGRADED);
        MunicipalSourceSlaPolicy.Evaluation recovered =
                evaluation(0, true, MunicipalSourceOperationalState.RECOVERING);
        when(healthService.izumSnapshot())
                .thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, failing))
                .thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, failing))
                .thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, recovered));
        when(healthService.snapshot(eq("istanbul-ispark-parks"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot(
                        "istanbul-ispark-parks",
                        evaluation(0, false, MunicipalSourceOperationalState.HEALTHY)));
        when(healthService.snapshot(eq("osm-geofabrik-turkey"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot(
                        "osm-geofabrik-turkey",
                        evaluation(0, false, MunicipalSourceOperationalState.HEALTHY)));

        MunicipalSourceMetrics metrics = new MunicipalSourceMetrics(registry, healthService, properties);
        metrics.registerGauges();

        assertThat(registry.find("parkio.municipal.source.seconds_since_success")
                        .tag("source_key", "istanbul-ispark-parks")
                        .gauge())
                .isNotNull();
        assertThat(registry.find("parkio.municipal.source.last_success_unixtime")
                        .tag("source_key", "istanbul-ispark-parks")
                        .gauge())
                .isNotNull();

        metrics.record(
                IzumMunicipalParkingAdapter.SOURCE_KEY,
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "read_timeout", "ignored"),
                Duration.ofMillis(12));
        metrics.record(
                IzumMunicipalParkingAdapter.SOURCE_KEY,
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.SUCCESS, 1, 1, 0, 0, 0, 1, 1, null, null),
                Duration.ofMillis(20));

        when(healthService.snapshot(eq("istanbul-ispark-parks"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot("istanbul-ispark-parks", failing))
                .thenReturn(snapshot("istanbul-ispark-parks", recovered));
        metrics.record(
                "istanbul-ispark-parks",
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "read_timeout", "ignored"),
                Duration.ofMillis(12));
        metrics.record(
                "istanbul-ispark-parks",
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.SUCCESS, 247, 247, 0, 0, 0, 247, 247, null, null),
                Duration.ofMillis(40));

        assertThat(registry.find("parkio.municipal.source.recoveries").counters())
                .anySatisfy(c -> assertThat(c.count()).isGreaterThanOrEqualTo(1.0));
        assertThat(registry.find("parkio.municipal.sync.retries_exhausted")
                        .tag("error_category", "read_timeout")
                        .counter())
                .isNotNull();
        assertThat(registry.find("parkio.municipal.sync.runs")
                        .tag("source_key", "istanbul-ispark-parks")
                        .tag("status", "SUCCESS")
                        .counter())
                .isNotNull();

        String joined = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .collect(Collectors.joining(","));
        assertThat(joined)
                .contains("source_mode")
                .contains("istanbul-ispark-parks")
                .doesNotContain("Read timed out")
                .doesNotContain("openapi.izmir")
                .doesNotContain("ignored")
                .doesNotContain("facility_id");
    }

    @Test
    void countsConsecutiveIncompleteSnapshotsAndResetsOnACompleteRun() {
        // CL-F22 (a): the gauge the MunicipalIzumIncompleteSnapshotsRepeated alert reads.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
        MunicipalSourceSlaPolicy.Evaluation healthy = evaluation(0, true, MunicipalSourceOperationalState.HEALTHY);
        when(healthService.izumSnapshot()).thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, healthy));
        when(healthService.snapshot(anyString(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> snapshot(inv.getArgument(0), healthy));
        MunicipalSourceMetrics metrics =
                new MunicipalSourceMetrics(registry, healthService, new MunicipalSourceProperties());
        metrics.registerGauges();
        String izum = IzumMunicipalParkingAdapter.SOURCE_KEY;
        var skipped = new MunicipalSyncResult(MunicipalSyncRunStatus.SUCCESS, 2, 2, 0, 0, 0, 2, 2, 0, 0, 3,
                null, null, true);
        var failed = new MunicipalSyncResult(MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "upstream_5xx", null);
        var complete = new MunicipalSyncResult(MunicipalSyncRunStatus.SUCCESS, 3, 3, 0, 0, 0, 3, 3, 0, 0, 3,
                null, null, false);

        metrics.record(izum, skipped, Duration.ZERO);
        metrics.record(izum, skipped, Duration.ZERO);
        metrics.record(izum, failed, Duration.ZERO);
        metrics.record(izum, skipped, Duration.ZERO);

        // A failed run in between neither counts nor ends the streak.
        assertThat(consecutiveIncompleteSnapshots(registry, izum)).isEqualTo(3.0);
        assertThat(registry.find("parkio.municipal.sync.reconciliation_skipped")
                        .tag("source_key", izum)
                        .tag("reason", "incomplete_snapshot")
                        .counter()
                        .count())
                .isEqualTo(3.0);

        metrics.record(izum, complete, Duration.ZERO);
        assertThat(consecutiveIncompleteSnapshots(registry, izum)).isZero();
        assertThat(consecutiveIncompleteSnapshots(registry, "istanbul-ispark-parks")).isZero();
    }

    @Test
    void tracksHowLongAFeedHasRepeatedItselfAndResetsOnAChange() {
        // CL-F22, owner option C: the operator-only gauges the MunicipalFeedUnchangedTooLong alert reads.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
        MunicipalSourceSlaPolicy.Evaluation healthy = evaluation(0, true, MunicipalSourceOperationalState.HEALTHY);
        when(healthService.izumSnapshot()).thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, healthy));
        when(healthService.snapshot(anyString(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> snapshot(inv.getArgument(0), healthy));
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIzum().setUnchangedFeedAlertAfter(Duration.ofMinutes(10));
        MunicipalSourceMetrics metrics = new MunicipalSourceMetrics(registry, healthService, properties);
        metrics.registerGauges();
        String izum = IzumMunicipalParkingAdapter.SOURCE_KEY;
        Instant t0 = Instant.parse("2026-10-05T08:00:00Z");

        // The streak counts from the fetch time of the run it repeats.
        metrics.record(izum, withFeedChange(new MunicipalFeedChange(true, t0, t0.plusSeconds(120))), Duration.ZERO);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", izum)).isEqualTo(120.0);
        metrics.record(izum, withFeedChange(new MunicipalFeedChange(true, t0.plusSeconds(120), t0.plusSeconds(240))),
                Duration.ZERO);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", izum)).isEqualTo(240.0);

        // A failed run, or one without a comparison, leaves the age where it was.
        metrics.record(izum, new MunicipalSyncResult(MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0,
                "upstream_5xx", null), Duration.ZERO);
        metrics.record(izum, withFeedChange(null), Duration.ZERO);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", izum)).isEqualTo(240.0);

        // A changed feed resets it, and the next repeat counts from the changed run.
        metrics.record(izum, withFeedChange(new MunicipalFeedChange(false, t0.plusSeconds(240), t0.plusSeconds(360))),
                Duration.ZERO);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", izum)).isZero();
        metrics.record(izum, withFeedChange(new MunicipalFeedChange(true, t0.plusSeconds(360), t0.plusSeconds(480))),
                Duration.ZERO);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", izum)).isEqualTo(120.0);

        // Thresholds come from configuration; İSPARK keeps the 4-hour default and its own age.
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_threshold_seconds", izum)).isEqualTo(600.0);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_threshold_seconds", "istanbul-ispark-parks"))
                .isEqualTo(14_400.0);
        assertThat(gauge(registry, "parkio.municipal.sync.unchanged_feed_seconds", "istanbul-ispark-parks")).isZero();
        assertThat(registry.find("parkio.municipal.sync.unchanged_feed_seconds")
                        .tag("source_key", "osm-geofabrik-turkey").gauge())
                .isNull();
    }

    private static MunicipalSyncResult withFeedChange(MunicipalFeedChange change) {
        return new MunicipalSyncResult(MunicipalSyncRunStatus.SUCCESS, 2, 2, 0, 0, 0, 2, 2, 0, 0, 2,
                null, null, false, change);
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String sourceKey) {
        return registry.find(name).tag("source_key", sourceKey).gauge().value();
    }

    private static double consecutiveIncompleteSnapshots(SimpleMeterRegistry registry, String sourceKey) {
        return registry.find("parkio.municipal.sync.consecutive_incomplete_snapshots")
                .tag("source_key", sourceKey)
                .gauge()
                .value();
    }

    @Test
    void failedSyncDoesNotAdvanceIsparkLastSuccessGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIspark().setEnabled(true);
        properties.getIspark().setSchedulerEnabled(true);

        MunicipalSourceSlaPolicy.Evaluation success =
                evaluation(0, false, MunicipalSourceOperationalState.HEALTHY);
        MunicipalSourceSlaPolicy.Evaluation failed =
                evaluation(1, false, MunicipalSourceOperationalState.DEGRADED);
        when(healthService.izumSnapshot())
                .thenReturn(snapshot(IzumMunicipalParkingAdapter.SOURCE_KEY, success));
        when(healthService.snapshot(eq("istanbul-ispark-parks"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot("istanbul-ispark-parks", success))
                .thenReturn(snapshot("istanbul-ispark-parks", failed));
        when(healthService.snapshot(eq("osm-geofabrik-turkey"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot(
                        "osm-geofabrik-turkey",
                        evaluation(0, false, MunicipalSourceOperationalState.HEALTHY)));

        MunicipalSourceMetrics metrics = new MunicipalSourceMetrics(registry, healthService, properties);
        metrics.registerGauges();

        double before = registry.find("parkio.municipal.source.last_success_unixtime")
                .tag("source_key", "istanbul-ispark-parks")
                .gauge()
                .value();

        metrics.record(
                "istanbul-ispark-parks",
                new MunicipalSyncResult(
                        MunicipalSyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, 0, "read_timeout", null),
                Duration.ofMillis(15));

        double after = registry.find("parkio.municipal.source.last_success_unixtime")
                .tag("source_key", "istanbul-ispark-parks")
                .gauge()
                .value();
        assertThat(after).isEqualTo(before);
        assertThat(registry.find("parkio.municipal.source.seconds_since_success")
                        .tag("source_key", "istanbul-ispark-parks")
                        .gauge()
                        .value())
                .isEqualTo(3600.0);
    }

    private static MunicipalSourceHealthService.Snapshot snapshot(
            String sourceKey, MunicipalSourceSlaPolicy.Evaluation evaluation) {
        MunicipalSourceOperatingMode mode = "osm-geofabrik-turkey".equals(sourceKey)
                ? MunicipalSourceOperatingMode.OPERATOR_IMPORTED
                : MunicipalSourceOperatingMode.SCHEDULED;
        return new MunicipalSourceHealthService.Snapshot(
                sourceKey,
                true,
                true,
                true,
                mode,
                evaluation,
                MunicipalOccupancyFreshness.STALE,
                300,
                900);
    }

    private static MunicipalSourceSlaPolicy.Evaluation evaluation(
            int consecutive, boolean recovered, MunicipalSourceOperationalState state) {
        return new MunicipalSourceSlaPolicy.Evaluation(
                consecutive,
                consecutive > 0 ? "FAILED" : "SUCCESS",
                Instant.parse("2026-07-30T20:00:00Z"),
                Instant.parse("2026-07-30T19:14:28Z"),
                consecutive > 0 ? 3600 : 10,
                consecutive > 0 ? "read_timeout" : null,
                consecutive,
                0,
                state,
                recovered);
    }
}
