package com.parkio.parking.application.recommendation.ranking.shadow;

import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Async fire-and-forget shadow evaluation after deterministic ranking.
 * The public recommendation path never waits on this orchestrator.
 */
@Component
public class ShadowRankingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ShadowRankingOrchestrator.class);

    static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    static final long CIRCUIT_COOLDOWN_MS = 30_000L;

    private final ShadowRankingProperties properties;
    private final ShadowParkingRanker ranker;
    private final ShadowRankingMetrics metrics;
    private final ShadowEvaluationStore store;
    private final RankingEvaluationService rankingEvaluationService;
    private final Clock clock;
    private final Semaphore concurrency;
    private final AtomicInteger consecutiveProviderErrors = new AtomicInteger();
    private final AtomicLong circuitOpenUntilEpochMs = new AtomicLong(0L);
    private final ExecutorService rankerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ShadowRankingOrchestrator(
            ShadowRankingProperties properties,
            ShadowParkingRanker ranker,
            ShadowRankingMetrics metrics,
            ShadowEvaluationStore store,
            RankingEvaluationService rankingEvaluationService,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.store = Objects.requireNonNull(store, "store");
        this.rankingEvaluationService = rankingEvaluationService;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.concurrency = new Semaphore(Math.max(1, properties.snapshot().maxConcurrent()), true);
    }

    /**
     * Non-blocking entry point. Never throws to callers; never blocks the public path.
     *
     * @param authoritativeLimited candidates in authoritative public order (already limited)
     * @param evaluationId optional privacy-safe evaluation token from WP-SPA-14B
     */
    public void maybeEvaluateAsync(
            RankingStatus authStatus,
            RankingVersion authVersion,
            List<ParkingCandidate> authoritativeLimited,
            boolean inventoryPartial,
            int radiusMeters,
            UUID evaluationId,
            Executor executor) {
        try {
            ShadowRankingProperties.ShadowConfiguration config = properties.snapshot();

            // Kill-switch / non-applied ranking: no store noise, no skip storm.
            if (!config.enabled() || authStatus != RankingStatus.APPLIED) {
                return;
            }

            metrics.recordRequest();
            if (isCircuitOpen()) {
                metrics.recordCircuitOpen();
                metrics.recordSkipped(ShadowRankingStatus.CIRCUIT_OPEN.name());
                storeSkipped(ShadowRankingStatus.CIRCUIT_OPEN, authVersion, inventoryPartial, List.of(), 0L);
                return;
            }
            if (config.sampleRate() <= 0.0
                    || ThreadLocalRandom.current().nextDouble() >= config.sampleRate()) {
                metrics.recordSkipped(ShadowRankingStatus.NOT_SAMPLED.name());
                storeSkipped(ShadowRankingStatus.NOT_SAMPLED, authVersion, inventoryPartial, List.of(), 0L);
                return;
            }
            if (authoritativeLimited == null || authoritativeLimited.isEmpty()) {
                metrics.recordSkipped(ShadowRankingStatus.BUDGET_SKIPPED.name());
                storeSkipped(ShadowRankingStatus.BUDGET_SKIPPED, authVersion, inventoryPartial, List.of(), 0L);
                return;
            }
            if (!concurrency.tryAcquire()) {
                metrics.recordSkipped(ShadowRankingStatus.BUDGET_SKIPPED.name());
                storeSkipped(ShadowRankingStatus.BUDGET_SKIPPED, authVersion, inventoryPartial, List.of(), 0L);
                return;
            }

            metrics.recordSampled();
            List<ParkingCandidate> capped = authoritativeLimited.stream()
                    .limit(config.maxCandidates())
                    .toList();
            Executor runOn = executor != null ? executor : Runnable::run;
            try {
                runOn.execute(() -> {
                    try {
                        evaluate(authVersion, capped, inventoryPartial, radiusMeters, evaluationId, config);
                    } catch (RuntimeException ex) {
                        log.debug(
                                "shadow ranking async evaluation failed type={}",
                                ex.getClass().getSimpleName());
                    } finally {
                        concurrency.release();
                    }
                });
            } catch (RuntimeException ex) {
                concurrency.release();
                metrics.recordSkipped(ShadowRankingStatus.BUDGET_SKIPPED.name());
                storeSkipped(ShadowRankingStatus.BUDGET_SKIPPED, authVersion, inventoryPartial, List.of(), 0L);
            }
        } catch (RuntimeException ex) {
            log.debug("shadow ranking orchestration failed type={}", ex.getClass().getSimpleName());
        }
    }

    /** Compatibility overload without evaluation correlation. */
    public void maybeEvaluateAsync(
            RankingStatus authStatus,
            RankingVersion authVersion,
            List<ParkingCandidate> authoritativeLimited,
            boolean inventoryPartial,
            int radiusMeters,
            Executor executor) {
        maybeEvaluateAsync(
                authStatus,
                authVersion,
                authoritativeLimited,
                inventoryPartial,
                radiusMeters,
                null,
                executor);
    }

    void evaluate(
            RankingVersion authVersion,
            List<ParkingCandidate> capped,
            boolean inventoryPartial,
            int radiusMeters,
            UUID evaluationId,
            ShadowRankingProperties.ShadowConfiguration config) {
        long started = System.nanoTime();
        ShadowRankingRequest request =
                ShadowFeatureExtractor.extract(capped, inventoryPartial, radiusMeters);

        List<String> authoritativeAliases =
                request.candidates().stream().map(ShadowCandidateFeatures::alias).toList();

        Future<ShadowRankingOutput> future = rankerExecutor.submit(() -> ranker.rank(request));
        try {
            ShadowRankingOutput output = future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            metrics.recordDuration(System.nanoTime() - started);

            if (!ShadowOutputValidator.isValid(request, output)) {
                consecutiveProviderErrors.set(0);
                metrics.recordInvalidOutput();
                store.add(new ShadowEvaluationRecord(
                        clock.instant(),
                        ShadowRankingStatus.INVALID_OUTPUT,
                        authVersion,
                        ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                        ShadowRankingConstants.SHADOW_RANKER_VERSION,
                        ShadowRankingConstants.PROMPT_VERSION,
                        inventoryPartial,
                        authoritativeAliases,
                        output == null ? List.of() : output.orderedCandidateAliases(),
                        request,
                        output,
                        null,
                        latencyMs));
                return;
            }

            ShadowComparison comparison =
                    ShadowComparison.compare(authoritativeAliases, output.orderedCandidateAliases());
            consecutiveProviderErrors.set(0);
            metrics.recordSuccess();
            metrics.recordComparison(comparison);
            store.add(new ShadowEvaluationRecord(
                    clock.instant(),
                    ShadowRankingStatus.SUCCESS,
                    authVersion,
                    ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                    ShadowRankingConstants.SHADOW_RANKER_VERSION,
                    ShadowRankingConstants.PROMPT_VERSION,
                    inventoryPartial,
                    authoritativeAliases,
                    output.orderedCandidateAliases(),
                    request,
                    output,
                    comparison,
                    latencyMs));
            if (rankingEvaluationService != null) {
                rankingEvaluationService.maybeAttachShadowOrder(
                        evaluationId,
                        authoritativeAliases,
                        output.orderedCandidateAliases(),
                        comparison.top1Agreement(),
                        comparison.top3Overlap(),
                        ShadowRankingConstants.SHADOW_RANKER_VERSION);
            }
        } catch (TimeoutException ex) {
            future.cancel(true);
            consecutiveProviderErrors.set(0);
            metrics.recordTimeout();
            metrics.recordDuration(System.nanoTime() - started);
            store.add(new ShadowEvaluationRecord(
                    clock.instant(),
                    ShadowRankingStatus.TIMEOUT,
                    authVersion,
                    ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                    ShadowRankingConstants.SHADOW_RANKER_VERSION,
                    ShadowRankingConstants.PROMPT_VERSION,
                    inventoryPartial,
                    authoritativeAliases,
                    List.of(),
                    request,
                    null,
                    null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } catch (Exception ex) {
            future.cancel(true);
            onProviderError();
            metrics.recordProviderError();
            metrics.recordDuration(System.nanoTime() - started);
            store.add(new ShadowEvaluationRecord(
                    clock.instant(),
                    ShadowRankingStatus.PROVIDER_ERROR,
                    authVersion,
                    ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                    ShadowRankingConstants.SHADOW_RANKER_VERSION,
                    ShadowRankingConstants.PROMPT_VERSION,
                    inventoryPartial,
                    authoritativeAliases,
                    List.of(),
                    request,
                    null,
                    null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }
    }

    private void onProviderError() {
        int failures = consecutiveProviderErrors.incrementAndGet();
        if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
            circuitOpenUntilEpochMs.set(System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS);
            consecutiveProviderErrors.set(0);
        }
    }

    private boolean isCircuitOpen() {
        long until = circuitOpenUntilEpochMs.get();
        if (until <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            circuitOpenUntilEpochMs.compareAndSet(until, 0L);
            return false;
        }
        return true;
    }

    private void storeSkipped(
            ShadowRankingStatus status,
            RankingVersion authVersion,
            boolean inventoryPartial,
            List<String> aliases,
            long latencyMs) {
        store.add(new ShadowEvaluationRecord(
                Instant.now(clock),
                status,
                authVersion,
                ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                ShadowRankingConstants.SHADOW_RANKER_VERSION,
                ShadowRankingConstants.PROMPT_VERSION,
                inventoryPartial,
                aliases,
                List.of(),
                null,
                null,
                null,
                latencyMs));
    }

}
