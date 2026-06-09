package com.parkio.parking.infrastructure.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.parkio.parking.application.ParkingApplicationService;
import org.junit.jupiter.api.Test;

class ParkingExpiryJobTest {

    @Test
    void delegatesConfiguredBatchSizeToApplicationService() {
        ParkingApplicationService parking = mock(ParkingApplicationService.class);
        ParkingExpiryJob job = new ParkingExpiryJob(parking, 25);

        job.expireElapsedSpots();

        verify(parking).expireElapsedSpots(25);
    }
}
