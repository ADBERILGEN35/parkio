package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.anpark.enabled",
        "parkio.municipal.anpark.scheduler-enabled"
}, havingValue = "true")
public class AnparkMunicipalSyncJob {
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;

    public AnparkMunicipalSyncJob(MunicipalFacilitySyncService service, MunicipalSourceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** Default 6h: inventory-only feed; no live occupancy cadence required. */
    @Scheduled(fixedDelayString = "${parkio.municipal.anpark.fixed-delay-ms:21600000}")
    public void sync() {
        Instant started = Instant.now();
        var result = service.sync(AnparkMunicipalParkingAdapter.SOURCE_KEY);
        metrics.record(AnparkMunicipalParkingAdapter.SOURCE_KEY, result, Duration.between(started, Instant.now()));
    }
}
