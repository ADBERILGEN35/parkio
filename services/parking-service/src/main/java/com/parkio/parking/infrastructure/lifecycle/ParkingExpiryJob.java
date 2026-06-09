package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.ParkingApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically expires elapsed parking spots in bounded, lock-safe batches. */
@Component
@ConditionalOnProperty(
        name = "parkio.lifecycle.parking-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ParkingExpiryJob {

    private final ParkingApplicationService parking;
    private final int batchSize;

    public ParkingExpiryJob(
            ParkingApplicationService parking,
            @Value("${parkio.lifecycle.parking-expiry.batch-size:100}") int batchSize) {
        this.parking = parking;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.parking-expiry.fixed-delay-ms:60000}")
    public void expireElapsedSpots() {
        parking.expireElapsedSpots(batchSize);
    }
}
