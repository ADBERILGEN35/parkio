package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.ParkingApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Wiring tests for the scheduled moderation timeout job. */
class ModerationTimeoutJobTest {

    private static final String METER = "parkio.parking.moderation.timeout.job.handled.count";

    @Test
    void delegatesToTheServiceWithTheConfiguredBatchSizeAndCountsHandledSpots() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(parking.processModerationTimeouts(25)).thenReturn(4);

        new ModerationTimeoutJob(parking, registry, 25).resolveOverdueModeration();

        verify(parking).processModerationTimeouts(25);
        assertThat(registry.get(METER).counter().count()).isEqualTo(4.0);
    }

    @Test
    void doesNotRecordAnythingWhenNoSpotIsOverdue() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(parking.processModerationTimeouts(anyInt())).thenReturn(0);

        new ModerationTimeoutJob(parking, registry, 100).resolveOverdueModeration();

        assertThat(registry.get(METER).counter().count()).isZero();
    }
}
