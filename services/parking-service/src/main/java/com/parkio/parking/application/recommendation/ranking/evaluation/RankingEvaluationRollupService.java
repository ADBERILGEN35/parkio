package com.parkio.parking.application.recommendation.ranking.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Privacy-safe long-horizon ranking evaluation rollups (WP-SPA-14D).
 *
 * <p>Never blocks recommendations. Closed UTC-hour slices are replaced idempotently. Raw cleanup
 * waits for watermark progress when rollups are enabled.
 */
@Service
public class RankingEvaluationRollupService {

    private static final Logger log = LoggerFactory.getLogger(RankingEvaluationRollupService.class);

    private final RankingEvaluationProperties properties;
    private final RankingEvaluationStore evaluationStore;
    private final RankingEvaluationRollupStore rollupStore;
    private final RankingEvaluationRollupMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RankingEvaluationRollupService(
            RankingEvaluationProperties properties,
            RankingEvaluationStore evaluationStore,
            RankingEvaluationRollupStore rollupStore,
            RankingEvaluationRollupMetrics metrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.evaluationStore = Objects.requireNonNull(evaluationStore, "evaluationStore");
        this.rollupStore = Objects.requireNonNull(rollupStore, "rollupStore");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isRollupEnabled() {
        return properties.snapshot().rollupEnabled();
    }

    /**
     * Process all closed UTC hour slices that are older than the grace window and not yet
     * completed. Fail-open: never throws to scheduler.
     */
    public int runRollupPass() {
        RankingEvaluationProperties.EvaluationConfiguration config = properties.snapshot();
        if (!config.rollupEnabled()) {
            return 0;
        }
        if (!running.compareAndSet(false, true)) {
            metrics.recordSkippedOverlap();
            return 0;
        }
        long started = System.currentTimeMillis();
        metrics.recordRun();
        int slices = 0;
        try {
            Instant now = clock.instant();
            Instant completedThrough =
                    rollupStore.findCompletedThrough().orElse(Instant.EPOCH);
            // Closed hours must end at least graceMinutes ago.
            Instant latestAllowedSliceEnd = RankingEvaluationRollupAccumulator.truncateToUtcHour(now)
                    .minus(Duration.ofMinutes(config.rollupGraceMinutes()));
            Instant cursor;
            if (completedThrough.equals(Instant.EPOCH)) {
                Instant bootstrap = findBootstrapSliceStart(now, latestAllowedSliceEnd, config);
                if (bootstrap == null) {
                    metrics.recordSuccess();
                    return 0;
                }
                cursor = bootstrap;
            } else {
                cursor = RankingEvaluationRollupAccumulator.truncateToUtcHour(completedThrough);
                if (cursor.isBefore(completedThrough)) {
                    cursor = cursor.plus(Duration.ofHours(1));
                }
                // completedThrough is exclusive end; next slice starts there when hour-aligned.
                if (completedThrough.getEpochSecond() % 3600L == 0) {
                    cursor = completedThrough;
                }
            }

            int maxSlices = Math.max(1, config.rollupMaxSlicesPerPass());
            while (slices < maxSlices) {
                Instant sliceStart = cursor;
                Instant sliceEnd = sliceStart.plus(Duration.ofHours(1));
                if (sliceEnd.isAfter(latestAllowedSliceEnd)) {
                    break;
                }
                processSlice(sliceStart, sliceEnd, now);
                slices++;
                cursor = sliceEnd;
            }
            metrics.recordSuccess();
            if (slices > 0) {
                log.info(
                        "ranking_evaluation_rollup_complete slices={} window=closed_hours",
                        slices);
            }
            return slices;
        } catch (RuntimeException ex) {
            metrics.recordFailure(ex.getClass().getSimpleName());
            log.warn(
                    "ranking_evaluation_rollup status=failed type={}",
                    ex.getClass().getSimpleName());
            return slices;
        } finally {
            metrics.recordDuration(System.currentTimeMillis() - started);
            running.set(false);
        }
    }

    public SliceResult processSlice(Instant sliceStart, Instant sliceEnd, Instant now) {
        List<RankingEvaluationSnapshot> snapshots =
                evaluationStore.listSnapshotsCreatedBetween(sliceStart, sliceEnd);
        List<UUID> ids = snapshots.stream().map(RankingEvaluationSnapshot::evaluationId).toList();
        List<RankingEvaluationOutcomeRecord> outcomes =
                evaluationStore.listOutcomesForEvaluations(ids);
        Map<UUID, List<RankingEvaluationOutcomeRecord>> byEval = new HashMap<>();
        for (RankingEvaluationOutcomeRecord outcome : outcomes) {
            byEval.computeIfAbsent(outcome.evaluationId(), ignored -> new ArrayList<>()).add(outcome);
        }

        Map<RankingEvaluationRollupKey, RankingEvaluationRollupAccumulator> cells =
                RankingEvaluationRollupAccumulator.newMap();

        for (RankingEvaluationSnapshot snapshot : snapshots) {
            RankingEvaluationRollupFeatureSummary features =
                    RankingEvaluationRollupFeatureSummary.parse(
                            objectMapper, snapshot.featuresJson(), snapshot.candidateCount());
            String candidateBucket =
                    RankingEvaluationService.candidateCountBucket(snapshot.candidateCount());
            String shadowVersion = snapshot.shadowRankerVersion() == null
                            || snapshot.shadowRankerVersion().isBlank()
                    ? RankingEvaluationRollupConstants.SHADOW_VERSION_NONE
                    : snapshot.shadowRankerVersion();

            // Evaluation scaffold under outcome NONE so evaluation counts accumulate even without outcomes.
            RankingEvaluationRollupKey scaffoldKey = new RankingEvaluationRollupKey(
                    sliceStart,
                    RankingEvaluationPlatform.UNKNOWN.name(),
                    snapshot.inventoryComposition(),
                    RankingEvaluationRollupConstants.OUTCOME_NONE,
                    RankingEvaluationRollupConstants.EVIDENCE_UNKNOWN,
                    snapshot.rankingVersion(),
                    shadowVersion,
                    snapshot.featureSchemaVersion(),
                    RankingEvaluationRollupConstants.EVALUATION_SCHEMA_VERSION,
                    candidateBucket,
                    features.freshnessMix,
                    features.zeroAvailabilityPresent,
                    features.highCapacityPresent,
                    snapshot.inventoryPartial());
            RankingEvaluationRollupAccumulator.get(cells, scaffoldKey).addEvaluationScaffold();

            List<RankingEvaluationOutcomeRecord> evalOutcomes =
                    byEval.getOrDefault(snapshot.evaluationId(), List.of());
            for (RankingEvaluationOutcomeRecord outcome : evalOutcomes) {
                int detRank = outcome.candidateOrdinal();
                Integer shadowRank =
                        RankingEvaluationOfflineEvaluator.shadowRankOf(snapshot, outcome.candidateOrdinal());
                boolean zeroSelected = features.zeroAt(outcome.candidateOrdinal());
                boolean staleSelected = features.staleAt(outcome.candidateOrdinal());
                boolean zeroShadowTop1 = false;
                boolean staleShadowPromoted = false;
                if (snapshot.shadowOrderByOrdinal() != null
                        && !snapshot.shadowOrderByOrdinal().isEmpty()) {
                    int shadowTop1Ordinal = snapshot.shadowOrderByOrdinal().getFirst();
                    zeroShadowTop1 = features.zeroAt(shadowTop1Ordinal);
                    // promoted: stale ranked above a live alternative relative to deterministic
                    staleShadowPromoted = isStaleShadowPromoted(snapshot, features);
                }
                RankingEvaluationRollupKey key = new RankingEvaluationRollupKey(
                        sliceStart,
                        outcome.platform().name(),
                        snapshot.inventoryComposition(),
                        outcome.outcomeType().name(),
                        RankingEvaluationRollupConstants.EVIDENCE_UNKNOWN,
                        snapshot.rankingVersion(),
                        shadowVersion,
                        snapshot.featureSchemaVersion(),
                        RankingEvaluationRollupConstants.EVALUATION_SCHEMA_VERSION,
                        candidateBucket,
                        features.freshnessMix,
                        features.zeroAvailabilityPresent,
                        features.highCapacityPresent,
                        snapshot.inventoryPartial());
                RankingEvaluationRollupAccumulator.get(cells, key)
                        .addOutcome(
                                detRank,
                                shadowRank,
                                zeroSelected,
                                zeroShadowTop1,
                                features.freshnessMix.equals("MIXED")
                                        || features.freshnessMix.equals("STALE_STATIC"),
                                staleSelected,
                                staleShadowPromoted);
            }
        }

        List<RankingEvaluationRollupRecord> rows = new ArrayList<>(cells.size());
        for (Map.Entry<RankingEvaluationRollupKey, RankingEvaluationRollupAccumulator> entry :
                cells.entrySet()) {
            rows.add(entry.getValue().toRecord(entry.getKey()));
        }
        RankingEvaluationPrivacyGuard.assertRollupRecordsAllowed(rows);
        rollupStore.replaceSlice(
                sliceStart,
                sliceEnd,
                rows,
                snapshots.size(),
                outcomes.size(),
                now == null ? clock.instant() : now);
        metrics.recordProcessed(snapshots.size(), outcomes.size(), rows.size());
        log.info(
                "ranking_evaluation_rollup_slice status=ok evaluations={} outcomes={} rows={}",
                snapshots.size(),
                outcomes.size(),
                rows.size());
        return new SliceResult(sliceStart, sliceEnd, snapshots.size(), outcomes.size(), rows.size());
    }

    public int cleanupExpiredRawRespectingWatermark() {
        RankingEvaluationProperties.EvaluationConfiguration config = properties.snapshot();
        if (!config.cleanupEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        try {
            int deleted;
            if (config.rollupEnabled()) {
                Instant completedThrough =
                        rollupStore.findCompletedThrough().orElse(Instant.EPOCH);
                deleted = rollupStore.deleteExpiredRawBeforeWatermark(
                        now, completedThrough, config.cleanupBatchSize());
            } else {
                deleted = evaluationStore.deleteExpiredBefore(now, config.cleanupBatchSize());
            }
            return deleted;
        } catch (RuntimeException ex) {
            log.warn(
                    "evaluation_cleanup status=failed type={}",
                    ex.getClass().getSimpleName());
            return 0;
        }
    }

    public int cleanupOldRollups() {
        RankingEvaluationProperties.EvaluationConfiguration config = properties.snapshot();
        if (!config.rollupEnabled() || !config.rollupCleanupEnabled()) {
            return 0;
        }
        Instant cutoff = clock.instant()
                .minus(Duration.ofDays(config.rollupRetentionDays()));
        Instant cutoffHour = RankingEvaluationRollupAccumulator.truncateToUtcHour(cutoff);
        try {
            int deleted = rollupStore.deleteRollupsOlderThan(cutoffHour, config.cleanupBatchSize());
            metrics.recordCleanupDeleted(deleted);
            return deleted;
        } catch (RuntimeException ex) {
            metrics.recordFailure("rollup_cleanup_" + ex.getClass().getSimpleName());
            return 0;
        }
    }

    private Instant findBootstrapSliceStart(
            Instant now,
            Instant latestClosedEnd,
            RankingEvaluationProperties.EvaluationConfiguration config) {
        Instant lookbackStart = now.minus(Duration.ofHours(config.retentionHours() + 2L));
        List<RankingEvaluationSnapshot> recent =
                evaluationStore.listSnapshotsCreatedBetween(lookbackStart, now);
        if (recent.isEmpty()) {
            return null;
        }
        Instant oldest = recent.getFirst().createdAt();
        for (RankingEvaluationSnapshot snapshot : recent) {
            if (snapshot.createdAt().isBefore(oldest)) {
                oldest = snapshot.createdAt();
            }
        }
        Instant start = RankingEvaluationRollupAccumulator.truncateToUtcHour(oldest);
        if (start.plus(Duration.ofHours(1)).isAfter(latestClosedEnd)) {
            return null;
        }
        return start;
    }

    private static boolean isStaleShadowPromoted(
            RankingEvaluationSnapshot snapshot, RankingEvaluationRollupFeatureSummary features) {
        List<Integer> det = snapshot.deterministicOrderByOrdinal();
        List<Integer> shadow = snapshot.shadowOrderByOrdinal();
        if (det == null || shadow == null || det.isEmpty() || shadow.isEmpty()) {
            return false;
        }
        if (!(features.freshnessMix.equals("LIVE_ONLY") || features.freshnessMix.equals("MIXED"))) {
            return false;
        }
        for (int staleOrdinal = 0; staleOrdinal < snapshot.candidateCount(); staleOrdinal++) {
            if (!features.staleAt(staleOrdinal)) {
                continue;
            }
            for (int liveOrdinal = 0; liveOrdinal < snapshot.candidateCount(); liveOrdinal++) {
                if (features.staleAt(liveOrdinal) || features.zeroAt(liveOrdinal)) {
                    continue;
                }
                int detStale = det.indexOf(staleOrdinal);
                int detLive = det.indexOf(liveOrdinal);
                int shStale = shadow.indexOf(staleOrdinal);
                int shLive = shadow.indexOf(liveOrdinal);
                if (detStale >= 0
                        && detLive >= 0
                        && shStale >= 0
                        && shLive >= 0
                        && detStale > detLive
                        && shStale < shLive) {
                    return true;
                }
            }
        }
        return false;
    }

    public record SliceResult(
            Instant sliceStart,
            Instant sliceEnd,
            int evaluationsProcessed,
            int outcomesProcessed,
            int rowsWritten) {}
}
