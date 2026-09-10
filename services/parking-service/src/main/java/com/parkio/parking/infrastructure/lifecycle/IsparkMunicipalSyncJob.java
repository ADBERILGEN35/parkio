package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.ispark.enabled",
        "parkio.municipal.ispark.scheduler-enabled"
}, havingValue = "true")
public class IsparkMunicipalSyncJob {
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;

    public IsparkMunicipalSyncJob(MunicipalFacilitySyncService service, MunicipalSourceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${parkio.municipal.ispark.fixed-delay-ms:120000}")
    public void sync() {
        Instant started = Instant.now();
        var result = service.sync(IsparkMunicipalParkingAdapter.SOURCE_KEY);
        metrics.record(IsparkMunicipalParkingAdapter.SOURCE_KEY, result, Duration.between(started, Instant.now()));
    }
}
