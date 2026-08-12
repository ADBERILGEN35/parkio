package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.infrastructure.konya.KonyaMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.konya.enabled",
        "parkio.municipal.konya.scheduler-enabled"
}, havingValue = "true")
public class KonyaMunicipalSyncJob {
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;

    public KonyaMunicipalSyncJob(MunicipalFacilitySyncService service, MunicipalSourceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** Default 24h: static/stale inventory; no live occupancy cadence required. */
    @Scheduled(fixedDelayString = "${parkio.municipal.konya.fixed-delay-ms:86400000}")
    public void sync() {
        Instant started = Instant.now();
        var result = service.sync(KonyaMunicipalParkingAdapter.SOURCE_KEY);
        metrics.record(KonyaMunicipalParkingAdapter.SOURCE_KEY, result, Duration.between(started, Instant.now()));
    }
}
