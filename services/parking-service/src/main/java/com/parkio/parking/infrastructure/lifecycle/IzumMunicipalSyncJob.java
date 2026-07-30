package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.izum.enabled",
        "parkio.municipal.izum.scheduler-enabled"
}, havingValue = "true")
public class IzumMunicipalSyncJob {
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;

    public IzumMunicipalSyncJob(MunicipalFacilitySyncService service, MunicipalSourceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${parkio.municipal.izum.fixed-delay-ms:120000}")
    public void sync() {
        Instant started = Instant.now();
        var result = service.sync(IzumMunicipalParkingAdapter.SOURCE_KEY);
        metrics.record(IzumMunicipalParkingAdapter.SOURCE_KEY, result, Duration.between(started, Instant.now()));
    }
}
