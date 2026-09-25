package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
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
