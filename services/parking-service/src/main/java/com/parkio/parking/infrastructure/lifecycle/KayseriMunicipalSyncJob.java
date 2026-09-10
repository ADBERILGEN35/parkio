package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.kayseri.KayseriMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.kayseri.enabled",
        "parkio.municipal.kayseri.scheduler-enabled"
}, havingValue = "true")
public class KayseriMunicipalSyncJob {
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;

    public KayseriMunicipalSyncJob(MunicipalFacilitySyncService service, MunicipalSourceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** Default 24h: static inventory; no live occupancy cadence required. */
    @Scheduled(fixedDelayString = "${parkio.municipal.kayseri.fixed-delay-ms:86400000}")
    public void sync() {
        Instant started = Instant.now();
        var result = service.sync(KayseriMunicipalParkingAdapter.SOURCE_KEY);
        metrics.record(KayseriMunicipalParkingAdapter.SOURCE_KEY, result, Duration.between(started, Instant.now()));
    }
}
