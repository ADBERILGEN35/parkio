package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Bounded-label municipal sync metrics. Labels are limited to source_key, status,
 * outcome, error_category, and operational/freshness state — never facility IDs,
 * URLs, external IDs, exception text, run IDs, or correlation IDs.
 */
@Component
public class MunicipalSourceMetrics {
    private static final String IZUM = IzumMunicipalParkingAdapter.SOURCE_KEY;

    private final MeterRegistry registry;
    private final MunicipalSourceHealthService healthService;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong secondsSinceSuccess = new AtomicLong(-1);
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(-1);
    private final AtomicLong lastRunEpochSeconds = new AtomicLong(-1);
    private final AtomicInteger staleRunningOperations = new AtomicInteger();
    private final AtomicInteger failuresInWindow = new AtomicInteger();
    private final AtomicReference<MunicipalSourceOperationalState> operationalState =
            new AtomicReference<>(MunicipalSourceOperationalState.UNKNOWN);
    private final AtomicReference<MunicipalOccupancyFreshness> occupancyFreshness =
            new AtomicReference<>(MunicipalOccupancyFreshness.UNAVAILABLE);
    private final AtomicInteger previousConsecutiveFailures = new AtomicInteger();

    public MunicipalSourceMetrics(MeterRegistry registry, MunicipalSourceHealthService healthService) {
        this.registry = registry;
        this.healthService = healthService;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("parkio.municipal.source.consecutive_failures", consecutiveFailures, AtomicInteger::get)
                .tag("source_key", IZUM)
                .register(registry);
        Gauge.builder("parkio.municipal.source.seconds_since_success", secondsSinceSuccess, AtomicLong::get)
                .tag("source_key", IZUM)
                .register(registry);
        Gauge.builder("parkio.municipal.source.last_success_unixtime", lastSuccessEpochSeconds, AtomicLong::get)
                .tag("source_key", IZUM)
                .register(registry);
        Gauge.builder("parkio.municipal.source.last_run_unixtime", lastRunEpochSeconds, AtomicLong::get)
                .tag("source_key", IZUM)
                .register(registry);
        Gauge.builder("parkio.municipal.source.stale_running_operations", staleRunningOperations, AtomicInteger::get)
                .tag("source_key", IZUM)
                .register(registry);
        Gauge.builder("parkio.municipal.source.failures_in_window", failuresInWindow, AtomicInteger::get)
                .tag("source_key", IZUM)
                .register(registry);
        for (MunicipalSourceOperationalState state : MunicipalSourceOperationalState.values()) {
            Gauge.builder("parkio.municipal.source.operational_state", this,
                            metrics -> metrics.operationalState.get() == state ? 1.0 : 0.0)
                    .tag("source_key", IZUM)
                    .tag("state", state.name())
                    .register(registry);
        }
        for (MunicipalOccupancyFreshness freshness : MunicipalOccupancyFreshness.values()) {
            Gauge.builder("parkio.municipal.source.occupancy_freshness", this,
                            metrics -> metrics.occupancyFreshness.get() == freshness ? 1.0 : 0.0)
                    .tag("source_key", IZUM)
                    .tag("state", freshness.name())
                    .register(registry);
        }
        refreshFromHistory();
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
        if (result.status() == MunicipalSyncRunStatus.FAILED
                && MunicipalSourceFailureCategory.isSchemaMismatchWire(error)) {
            registry.counter("parkio.municipal.sync.schema_mismatch",
                            "source_key", sourceKey)
                    .increment();
        }
        if (result.status() == MunicipalSyncRunStatus.FAILED) {
            registry.counter("parkio.municipal.sync.retries_exhausted",
                            "source_key", sourceKey,
                            "error_category", error)
                    .increment();
        }

        if (IZUM.equals(sourceKey)) {
            int prior = previousConsecutiveFailures.get();
            applySnapshot(healthService.izumSnapshot());
            int current = consecutiveFailures.get();
            if ((result.status() == MunicipalSyncRunStatus.SUCCESS
                    || result.status() == MunicipalSyncRunStatus.PARTIAL_SUCCESS)
                    && prior > 0
                    && current == 0) {
                registry.counter("parkio.municipal.source.recoveries",
                                "source_key", sourceKey)
                        .increment();
            }
            previousConsecutiveFailures.set(current);
        }
    }

    public void refreshFromHistory() {
        applySnapshot(healthService.izumSnapshot());
        previousConsecutiveFailures.set(consecutiveFailures.get());
    }

    private void applySnapshot(MunicipalSourceHealthService.Snapshot snapshot) {
        consecutiveFailures.set(snapshot.consecutiveFailures());
        secondsSinceSuccess.set(snapshot.secondsSinceSuccess());
        if (snapshot.evaluation().lastSuccessAt() != null) {
            lastSuccessEpochSeconds.set(snapshot.evaluation().lastSuccessAt().getEpochSecond());
        } else {
            lastSuccessEpochSeconds.set(-1);
        }
        if (snapshot.evaluation().lastRunAt() != null) {
            lastRunEpochSeconds.set(snapshot.evaluation().lastRunAt().getEpochSecond());
        } else {
            lastRunEpochSeconds.set(-1);
        }
        staleRunningOperations.set(snapshot.evaluation().staleRunningOperations());
        failuresInWindow.set(snapshot.evaluation().failuresInWindow());
        operationalState.set(snapshot.operationalState());
        occupancyFreshness.set(snapshot.occupancyFreshness());
    }
}
