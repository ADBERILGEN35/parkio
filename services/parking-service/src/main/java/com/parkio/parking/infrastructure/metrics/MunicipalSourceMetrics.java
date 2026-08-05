package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.MunicipalSourceHealthService;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
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
 * Bounded-label municipal sync metrics. Labels are limited to source_key, source_mode,
 * status, outcome, error_category, and operational/freshness state — never facility IDs,
 * URLs, external IDs, exception text, run IDs, or correlation IDs.
 */
@Component
public class MunicipalSourceMetrics {
    private static final String IZUM = IzumMunicipalParkingAdapter.SOURCE_KEY;
    private static final String OSM = MunicipalSourceIdentity.OSM;

    private final MeterRegistry registry;
    private final MunicipalSourceHealthService healthService;
    private final MunicipalSourceProperties properties;

    private final SourceGaugeState izum = new SourceGaugeState();
    private final SourceGaugeState osm = new SourceGaugeState();

    public MunicipalSourceMetrics(
            MeterRegistry registry,
            MunicipalSourceHealthService healthService,
            MunicipalSourceProperties properties) {
        this.registry = registry;
        this.healthService = healthService;
        this.properties = properties;
    }

    @PostConstruct
    void registerGauges() {
        registerSourceGauges(IZUM, MunicipalSourceOperatingMode.SCHEDULED, izum);
        registerSourceGauges(OSM, MunicipalSourceOperatingMode.OPERATOR_IMPORTED, osm);
        refreshFromHistory();
    }

    private void registerSourceGauges(
            String sourceKey, MunicipalSourceOperatingMode defaultMode, SourceGaugeState state) {
        String mode = resolveModeLabel(sourceKey, defaultMode);
        Gauge.builder("parkio.municipal.source.consecutive_failures", state.consecutiveFailures, AtomicInteger::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.source.seconds_since_success", state.secondsSinceSuccess, AtomicLong::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.source.last_success_unixtime", state.lastSuccessEpochSeconds, AtomicLong::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.source.last_run_unixtime", state.lastRunEpochSeconds, AtomicLong::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.source.stale_running_operations", state.staleRunningOperations, AtomicInteger::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.source.failures_in_window", state.failuresInWindow, AtomicInteger::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        Gauge.builder("parkio.municipal.sync.active_links", state.lastActiveLinkCount, AtomicInteger::get)
                .tag("source_key", sourceKey)
                .tag("source_mode", mode)
                .register(registry);
        for (MunicipalSourceOperationalState operationalState : MunicipalSourceOperationalState.values()) {
            Gauge.builder("parkio.municipal.source.operational_state", state,
                            gauges -> gauges.operationalState.get() == operationalState ? 1.0 : 0.0)
                    .tag("source_key", sourceKey)
                    .tag("source_mode", mode)
                    .tag("state", operationalState.name())
                    .register(registry);
        }
        for (MunicipalOccupancyFreshness freshness : MunicipalOccupancyFreshness.values()) {
            Gauge.builder("parkio.municipal.source.occupancy_freshness", state,
                            gauges -> gauges.occupancyFreshness.get() == freshness ? 1.0 : 0.0)
                    .tag("source_key", sourceKey)
                    .tag("source_mode", mode)
                    .tag("state", freshness.name())
                    .register(registry);
        }
    }

    private String resolveModeLabel(String sourceKey, MunicipalSourceOperatingMode fallback) {
        try {
            return com.parkio.parking.application.MunicipalSourceOperatingModePolicy
                    .resolve(sourceKey, properties)
                    .name();
        } catch (RuntimeException ex) {
            return fallback.name();
        }
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
        registry.counter("parkio.municipal.sync.facilities",
                        "source_key", sourceKey, "outcome", "deactivated")
                .increment(result.recordsDeactivated());
        registry.counter("parkio.municipal.sync.facilities",
                        "source_key", sourceKey, "outcome", "reactivated")
                .increment(result.recordsReactivated());
        SourceGaugeState syncState = stateFor(sourceKey);
        if (syncState != null) {
            syncState.lastActiveLinkCount.set(result.activeLinkCount());
        }
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

        SourceGaugeState state = stateFor(sourceKey);
        if (state != null) {
            int prior = state.previousConsecutiveFailures.get();
            applySnapshot(state, snapshotFor(sourceKey));
            int current = state.consecutiveFailures.get();
            if ((result.status() == MunicipalSyncRunStatus.SUCCESS
                    || result.status() == MunicipalSyncRunStatus.PARTIAL_SUCCESS)
                    && prior > 0
                    && current == 0) {
                registry.counter("parkio.municipal.source.recoveries",
                                "source_key", sourceKey,
                                "source_mode", resolveModeLabel(
                                        sourceKey, MunicipalSourceOperatingMode.SCHEDULED))
                        .increment();
            }
            state.previousConsecutiveFailures.set(current);
        }
    }

    public void refreshFromHistory() {
        applySnapshot(izum, healthService.izumSnapshot());
        izum.previousConsecutiveFailures.set(izum.consecutiveFailures.get());
        applySnapshot(osm, osmSnapshot());
        osm.previousConsecutiveFailures.set(osm.consecutiveFailures.get());
    }

    /** Refresh OSM SLA gauges after operator import without requiring a MunicipalSyncResult. */
    public void refreshOsmFromHistory() {
        applySnapshot(osm, osmSnapshot());
        osm.previousConsecutiveFailures.set(osm.consecutiveFailures.get());
    }

    private MunicipalSourceHealthService.Snapshot snapshotFor(String sourceKey) {
        if (IZUM.equals(sourceKey)) {
            return healthService.izumSnapshot();
        }
        if (OSM.equals(sourceKey)) {
            return osmSnapshot();
        }
        return null;
    }

    private MunicipalSourceHealthService.Snapshot osmSnapshot() {
        boolean sourceEnabled = properties.getOsm().isImportEnabled()
                || properties.getOsm().isPublicationEnabled();
        return healthService.snapshot(
                OSM, sourceEnabled, properties.getOsm().isSchedulerEnabled());
    }

    private SourceGaugeState stateFor(String sourceKey) {
        if (IZUM.equals(sourceKey)) {
            return izum;
        }
        if (OSM.equals(sourceKey)) {
            return osm;
        }
        return null;
    }

    private static void applySnapshot(SourceGaugeState state, MunicipalSourceHealthService.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        state.consecutiveFailures.set(snapshot.consecutiveFailures());
        state.secondsSinceSuccess.set(snapshot.secondsSinceSuccess());
        if (snapshot.evaluation().lastSuccessAt() != null) {
            state.lastSuccessEpochSeconds.set(snapshot.evaluation().lastSuccessAt().getEpochSecond());
        } else {
            state.lastSuccessEpochSeconds.set(-1);
        }
        if (snapshot.evaluation().lastRunAt() != null) {
            state.lastRunEpochSeconds.set(snapshot.evaluation().lastRunAt().getEpochSecond());
        } else {
            state.lastRunEpochSeconds.set(-1);
        }
        state.staleRunningOperations.set(snapshot.evaluation().staleRunningOperations());
        state.failuresInWindow.set(snapshot.evaluation().failuresInWindow());
        state.operationalState.set(snapshot.operationalState());
        state.occupancyFreshness.set(snapshot.occupancyFreshness());
    }

    private static final class SourceGaugeState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong secondsSinceSuccess = new AtomicLong(-1);
        private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(-1);
        private final AtomicLong lastRunEpochSeconds = new AtomicLong(-1);
        private final AtomicInteger staleRunningOperations = new AtomicInteger();
        private final AtomicInteger failuresInWindow = new AtomicInteger();
        private final AtomicInteger lastActiveLinkCount = new AtomicInteger();
        private final AtomicReference<MunicipalSourceOperationalState> operationalState =
                new AtomicReference<>(MunicipalSourceOperationalState.UNKNOWN);
        private final AtomicReference<MunicipalOccupancyFreshness> occupancyFreshness =
                new AtomicReference<>(MunicipalOccupancyFreshness.UNAVAILABLE);
        private final AtomicInteger previousConsecutiveFailures = new AtomicInteger();
    }
}
