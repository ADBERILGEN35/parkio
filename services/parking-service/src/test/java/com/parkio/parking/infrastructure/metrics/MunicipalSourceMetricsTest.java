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
        when(healthService.snapshot(eq("osm-geofabrik-turkey"), anyBoolean(), anyBoolean()))
                .thenReturn(snapshot(
                        "osm-geofabrik-turkey",
                        evaluation(0, false, MunicipalSourceOperationalState.HEALTHY)));

        MunicipalSourceMetrics metrics = new MunicipalSourceMetrics(registry, healthService, properties);
        metrics.registerGauges();

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

        assertThat(registry.find("parkio.municipal.source.recoveries").counter()).isNotNull();
        assertThat(registry.find("parkio.municipal.source.recoveries").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("parkio.municipal.sync.retries_exhausted")
                        .tag("error_category", "read_timeout")
                        .counter())
                .isNotNull();

        String joined = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .collect(Collectors.joining(","));
        assertThat(joined)
                .contains("source_mode")
                .doesNotContain("Read timed out")
                .doesNotContain("openapi.izmir")
                .doesNotContain("ignored");
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
