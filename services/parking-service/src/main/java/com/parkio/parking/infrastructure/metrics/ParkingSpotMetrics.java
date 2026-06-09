package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Parking-spot lifecycle gauges exported at {@code /actuator/prometheus}:
 * {@code parkio.parking.{active,verified,suspicious,expired}.count}.
 *
 * <p>Each gauge issues one cheap COUNT query per scrape (15s locally). The expiry job
 * additionally exposes the {@code parkio.parking.expiry.job.expired.count} counter
 * (see {@code ParkingExpiryJob}).
 */
@Component
public class ParkingSpotMetrics {

    private final ParkingSpotJpaRepository spots;

    public ParkingSpotMetrics(ParkingSpotJpaRepository spots, MeterRegistry registry) {
        this.spots = spots;
        register(registry, "parkio.parking.active.count", ParkingSpotStatus.ACTIVE,
                "Parking spots currently ACTIVE");
        register(registry, "parkio.parking.verified.count", ParkingSpotStatus.VERIFIED,
                "Parking spots currently VERIFIED");
        register(registry, "parkio.parking.suspicious.count", ParkingSpotStatus.SUSPICIOUS,
                "Parking spots currently SUSPICIOUS");
        register(registry, "parkio.parking.expired.count", ParkingSpotStatus.EXPIRED,
                "Parking spots in the terminal EXPIRED state");
    }

    private void register(MeterRegistry registry, String name, ParkingSpotStatus status, String description) {
        Gauge.builder(name, this, m -> m.spots.countByStatus(status))
                .description(description)
                .register(registry);
    }
}
