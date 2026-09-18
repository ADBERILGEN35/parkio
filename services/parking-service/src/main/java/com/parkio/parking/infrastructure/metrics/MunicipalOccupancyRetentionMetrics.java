package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.MunicipalOccupancyRetentionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Bounded municipal occupancy retention metrics (no facility/source high-cardinality labels).
 */
@Component
public class MunicipalOccupancyRetentionMetrics implements MunicipalOccupancyRetentionService.Metrics {

    private final MeterRegistry registry;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(-1);
    private final AtomicLong lastDeletedRows = new AtomicLong(0);
    private final AtomicLong lastDurationMs = new AtomicLong(0);
    private Counter failureCounter;
    private Counter skippedCounter;
    private Counter deletedCounter;
    private Timer durationTimer;

    public MunicipalOccupancyRetentionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("parkio.municipal.occupancy.retention.last_success_unixtime",
                        lastSuccessEpochSeconds, AtomicLong::get)
                .register(registry);
        Gauge.builder("parkio.municipal.occupancy.retention.last_deleted_rows",
                        lastDeletedRows, AtomicLong::get)
                .register(registry);
        Gauge.builder("parkio.municipal.occupancy.retention.last_duration_ms",
                        lastDurationMs, AtomicLong::get)
                .register(registry);
        failureCounter = Counter.builder("parkio.municipal.occupancy.retention.failures")
                .register(registry);
        skippedCounter = Counter.builder("parkio.municipal.occupancy.retention.skipped")
                .register(registry);
        deletedCounter = Counter.builder("parkio.municipal.occupancy.retention.deleted_rows")
                .register(registry);
        durationTimer = Timer.builder("parkio.municipal.occupancy.retention.duration")
                .register(registry);
    }

    @Override
    public void recordSuccess(int deletedRows, long durationMs, Instant successAt) {
        lastDeletedRows.set(Math.max(0, deletedRows));
        lastDurationMs.set(Math.max(0, durationMs));
        if (successAt != null) {
            lastSuccessEpochSeconds.set(successAt.getEpochSecond());
        }
        deletedCounter.increment(Math.max(0, deletedRows));
        durationTimer.record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    @Override
    public void recordFailure() {
        failureCounter.increment();
    }

    @Override
    public void recordSkipped(String reason) {
        skippedCounter.increment();
    }

    /** Test helper — last success epoch seconds gauge value. */
    public long lastSuccessEpochSeconds() {
        return lastSuccessEpochSeconds.get();
    }
}
