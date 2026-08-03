package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalDataSourceRepository;
import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Assembles deterministic municipal source health/SLA snapshots from persisted run history. */
public class MunicipalSourceHealthService {
    public record Snapshot(
            String sourceKey,
            boolean municipalEnabled,
            boolean sourceEnabled,
            boolean schedulerEnabled,
            MunicipalSourceOperatingMode operatingMode,
            MunicipalSourceSlaPolicy.Evaluation evaluation,
            MunicipalOccupancyFreshness occupancyFreshness,
            long agingAfterSeconds,
            long staleAfterSeconds) {

        public int consecutiveFailures() {
            return evaluation.consecutiveFailures();
        }

        public MunicipalSourceOperationalState operationalState() {
            return evaluation.operationalState();
        }

        public long secondsSinceSuccess() {
            return evaluation.secondsSinceSuccess();
        }
    }

    private final MunicipalDataSourceRepository sources;
    private final MunicipalSourceSyncRunRepository runs;
    private final Clock clock;
    private final MunicipalSourceSlaPolicy.Thresholds thresholds;
    private final MunicipalSourceProperties properties;
    private final boolean municipalEnabled;
    private final boolean izumEnabled;
    private final boolean izumSchedulerEnabled;
    private final String izumSourceKey;

    public MunicipalSourceHealthService(
            MunicipalDataSourceRepository sources,
            MunicipalSourceSyncRunRepository runs,
            Clock clock,
            MunicipalSourceSlaPolicy.Thresholds thresholds,
            MunicipalSourceProperties properties,
            boolean municipalEnabled,
            boolean izumEnabled,
            boolean izumSchedulerEnabled,
            String izumSourceKey) {
        this.sources = sources;
        this.runs = runs;
        this.clock = clock;
        this.thresholds = thresholds;
        this.properties = properties;
        this.municipalEnabled = municipalEnabled;
        this.izumEnabled = izumEnabled;
        this.izumSchedulerEnabled = izumSchedulerEnabled;
        this.izumSourceKey = izumSourceKey;
    }

    public Snapshot izumSnapshot() {
        return snapshot(izumSourceKey, izumEnabled, izumSchedulerEnabled);
    }

    public Snapshot snapshot(String sourceKey, boolean sourceEnabled, boolean schedulerEnabled) {
        Instant now = clock.instant();
        MunicipalSourceOperatingMode mode =
                MunicipalSourceOperatingModePolicy.resolve(sourceKey, properties);
        boolean sourceModeSlaEnabled = properties.getOps().isSourceModeSlaEnabled();

        if (!municipalEnabled || !sourceEnabled) {
            MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                    municipalEnabled,
                    sourceEnabled,
                    schedulerEnabled,
                    mode,
                    sourceModeSlaEnabled,
                    List.of(),
                    null,
                    0,
                    0,
                    now,
                    thresholds);
            return new Snapshot(
                    sourceKey,
                    municipalEnabled,
                    sourceEnabled,
                    schedulerEnabled,
                    mode,
                    evaluation,
                    MunicipalOccupancyFreshness.UNAVAILABLE,
                    0,
                    0);
        }

        Optional<MunicipalDataSourceRepository.Source> source = sources.findBySourceKey(sourceKey);
        if (source.isEmpty()) {
            MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                    true,
                    true,
                    schedulerEnabled,
                    mode,
                    sourceModeSlaEnabled,
                    List.of(),
                    null,
                    0,
                    0,
                    now,
                    thresholds);
            return new Snapshot(
                    sourceKey,
                    true,
                    true,
                    schedulerEnabled,
                    mode,
                    evaluation,
                    MunicipalOccupancyFreshness.UNAVAILABLE,
                    0,
                    0);
        }

        MunicipalDataSourceRepository.Source value = source.get();
        List<MunicipalSourceSyncRunRepository.CompletedRunView> recent =
                runs.findRecentCompleted(value.id(), MunicipalSourceSlaPolicy.DEFAULT_HISTORY_BOUND);
        List<MunicipalSourceSlaPolicy.CompletedRun> mapped = recent.stream()
                .map(row -> new MunicipalSourceSlaPolicy.CompletedRun(
                        row.status(), row.errorCategory(), row.startedAt(), row.completedAt()))
                .toList();

        Instant lastSuccess = value.lastSuccessfulSyncAt();
        if (lastSuccess == null) {
            lastSuccess = runs.findLatestSuccessAt(value.id()).orElse(null);
        }

        Instant windowStart = now.minus(Duration.ofHours(24));
        int failuresInWindow = runs.countFailuresSince(value.id(), windowStart);
        Instant staleCutoff = now.minusSeconds(thresholds.staleRunningAfterSeconds());
        int staleRunning = runs.countStaleRunning(value.id(), staleCutoff);

        MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                true,
                true,
                schedulerEnabled,
                mode,
                sourceModeSlaEnabled,
                mapped,
                lastSuccess,
                failuresInWindow,
                staleRunning,
                now,
                thresholds);

        MunicipalOccupancyFreshness freshness = MunicipalSourceSlaPolicy.occupancyFreshness(
                lastSuccess,
                now,
                value.agingAfterSeconds(),
                value.staleAfterSeconds());

        return new Snapshot(
                sourceKey,
                true,
                true,
                schedulerEnabled,
                mode,
                evaluation,
                freshness,
                value.agingAfterSeconds(),
                value.staleAfterSeconds());
    }
}
