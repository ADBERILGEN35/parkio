package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.ParkingApplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter expiredCounter;

    public ParkingExpiryJob(
            ParkingApplicationService parking,
            MeterRegistry meterRegistry,
            @Value("${parkio.lifecycle.parking-expiry.batch-size:100}") int batchSize) {
        this.parking = parking;
        this.batchSize = batchSize;
        this.expiredCounter = Counter.builder("parkio.parking.expiry.job.expired.count")
                .description("Parking spots transitioned to EXPIRED by the expiry job")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.parking-expiry.fixed-delay-ms:60000}")
    public void expireElapsedSpots() {
        int expired = parking.expireElapsedSpots(batchSize);
        if (expired > 0) {
            expiredCounter.increment(expired);
        }
    }
}
