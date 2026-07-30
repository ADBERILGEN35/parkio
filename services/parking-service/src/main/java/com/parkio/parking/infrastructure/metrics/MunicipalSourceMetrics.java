package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Bounded-label municipal sync metrics. Labels are limited to source_key, status,
 * and error_category — never facility IDs, URLs, external IDs, or exception text.
 */
@Component
public class MunicipalSourceMetrics {
    private final MeterRegistry registry;

    public MunicipalSourceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String sourceKey, MunicipalSyncResult result, Duration duration) {
        String error = result.errorCategory() == null ? "none" : result.errorCategory();
        registry.counter("parkio.municipal.sync.runs",
                        "source_key", sourceKey,
                        "status", result.status().name(),
                        "error_category", error)
                .increment();
        Timer.builder("parkio.municipal.sync.duration")
                .tag("source_key", sourceKey)
                .tag("status", result.status().name())
                .register(registry)
                .record(duration == null ? Duration.ZERO : duration);
        registry.counter("parkio.municipal.sync.records",
                        "source_key", sourceKey, "outcome", "received")
                .increment(result.recordsReceived());
        registry.counter("parkio.municipal.sync.records",
                        "source_key", sourceKey, "outcome", "accepted")
                .increment(result.recordsAccepted());
        registry.counter("parkio.municipal.sync.records",
                        "source_key", sourceKey, "outcome", "rejected")
                .increment(result.recordsRejected());
        registry.counter("parkio.municipal.sync.facilities",
                        "source_key", sourceKey, "outcome", "inserted")
                .increment(result.recordsInserted());
        registry.counter("parkio.municipal.sync.facilities",
                        "source_key", sourceKey, "outcome", "updated")
                .increment(result.recordsUpdated());
        registry.counter("parkio.municipal.sync.facilities",
                        "source_key", sourceKey, "outcome", "unchanged")
                .increment(result.recordsUnchanged());
        registry.counter("parkio.municipal.sync.occupancy",
                        "source_key", sourceKey, "outcome", "inserted")
                .increment(result.occupancyInserted());
        if (result.status() == MunicipalSyncRunStatus.FAILED && "contract".equals(error)) {
            registry.counter("parkio.municipal.sync.schema_mismatch",
                            "source_key", sourceKey)
                    .increment();
        }
    }
}
