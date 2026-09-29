package com.parkio.parking.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class MunicipalSourceHealthIndicatorTest {

    @Test
    void livenessRemainsUpAndDetailsAreBoundedForIzumAndIspark() {
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
        MunicipalSourceProperties properties = new MunicipalSourceProperties();
        properties.getIspark().setEnabled(true);
        properties.getIspark().setSchedulerEnabled(true);

        MunicipalSourceSlaPolicy.Evaluation evaluation = new MunicipalSourceSlaPolicy.Evaluation(
                4,
                "FAILED",
                Instant.parse("2026-07-30T20:00:00Z"),
                Instant.parse("2026-07-30T19:14:28Z"),
                3600,
                "read_timeout",
                12,
                0,
                MunicipalSourceOperationalState.CRITICAL,
                false);
        MunicipalSourceSlaPolicy.Evaluation isparkEvaluation = new MunicipalSourceSlaPolicy.Evaluation(
                0,
                "SUCCESS",
                Instant.parse("2026-07-30T20:01:00Z"),
                Instant.parse("2026-07-30T20:01:00Z"),
                10,
                null,
                0,
                0,
                MunicipalSourceOperationalState.HEALTHY,
                false);
        when(healthService.izumSnapshot()).thenReturn(new MunicipalSourceHealthService.Snapshot(
                "izmir-izum-otoparklar",
                true,
                true,
                true,
                com.parkio.parking.externalsource.MunicipalSourceOperatingMode.SCHEDULED,
                evaluation,
                MunicipalOccupancyFreshness.STALE,
                300,
                900));
        when(healthService.snapshot(
                        eq(IsparkMunicipalParkingAdapter.SOURCE_KEY), anyBoolean(), anyBoolean()))
                .thenReturn(new MunicipalSourceHealthService.Snapshot(
                        IsparkMunicipalParkingAdapter.SOURCE_KEY,
                        true,
                        true,
                        true,
                        com.parkio.parking.externalsource.MunicipalSourceOperatingMode.SCHEDULED,
                        isparkEvaluation,
                        MunicipalOccupancyFreshness.LIVE,
                        300,
                        900));

        Health health = new MunicipalSourceHealthIndicator(healthService, properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("izumSourceMode", "SCHEDULED")
                .containsEntry("izumOperationalState", "CRITICAL")
                .containsEntry("izumOccupancyFreshness", "STALE")
                .containsEntry("izumConsecutiveFailures", 4)
                .containsEntry("izumLastErrorCategory", "read_timeout")
                .containsEntry("izumStatus", "failing")
                .containsEntry("isparkSourceMode", "SCHEDULED")
                .containsEntry("isparkOperationalState", "HEALTHY")
                .containsEntry("isparkOccupancyFreshness", "LIVE")
                .containsEntry("isparkStatus", "healthy")
                .doesNotContainKey("exception")
                .doesNotContainKey("stackTrace");
        assertThat(health.getDetails().values().stream().map(Object::toString))
                .noneMatch(value -> value.contains("openapi.izmir.bel.tr")
                        || value.contains("api.ibb.gov.tr")
                        || value.contains("Read timed out")
                        || value.contains("stack"));
    }
}
