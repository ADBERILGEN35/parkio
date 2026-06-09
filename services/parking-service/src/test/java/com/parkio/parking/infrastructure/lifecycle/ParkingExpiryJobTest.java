package com.parkio.parking.infrastructure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.ParkingApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ParkingExpiryJobTest {

    @Test
    void delegatesConfiguredBatchSizeToApplicationService() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        ParkingExpiryJob job = new ParkingExpiryJob(parking, new SimpleMeterRegistry(), 25);

        job.expireElapsedSpots();

        verify(parking).expireElapsedSpots(25);
    }

    @Test
    void countsExpiredSpotsOnTheJobCounter() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        when(parking.expireElapsedSpots(25)).thenReturn(3).thenReturn(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParkingExpiryJob job = new ParkingExpiryJob(parking, registry, 25);

        job.expireElapsedSpots();
        job.expireElapsedSpots();

        assertThat(registry.counter("parkio.parking.expiry.job.expired.count").count()).isEqualTo(3.0);
    }
}
