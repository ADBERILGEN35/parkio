package com.parkio.parking.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.application.MunicipalSourceSlaPolicy;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

class MunicipalSourceHealthIndicatorTest {

    @Test
    void livenessRemainsUpAndDetailsAreBounded() {
        MunicipalSourceHealthService healthService = mock(MunicipalSourceHealthService.class);
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
        when(healthService.izumSnapshot()).thenReturn(new MunicipalSourceHealthService.Snapshot(
                "izmir-izum-otoparklar",
                true,
                true,
                true,
                evaluation,
                MunicipalOccupancyFreshness.STALE,
                300,
                900));

        Health health = new MunicipalSourceHealthIndicator(healthService).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("izumOperationalState", "CRITICAL")
                .containsEntry("izumOccupancyFreshness", "STALE")
                .containsEntry("izumConsecutiveFailures", 4)
                .containsEntry("izumLastErrorCategory", "read_timeout")
                .containsEntry("izumStatus", "failing")
                .doesNotContainKey("exception")
                .doesNotContainKey("stackTrace");
        assertThat(health.getDetails().values().stream().map(Object::toString))
                .noneMatch(value -> value.contains("openapi.izmir.bel.tr")
                        || value.contains("Read timed out")
                        || value.contains("stack"));
    }
}
