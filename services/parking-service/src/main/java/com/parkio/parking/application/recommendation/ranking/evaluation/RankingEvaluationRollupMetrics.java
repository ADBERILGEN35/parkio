package com.parkio.parking.application.recommendation.ranking.evaluation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics under {@code parkio.spa.ranking.evaluation.rollup.*}. */
@Component
public class RankingEvaluationRollupMetrics {

    private final MeterRegistry registry;

    public RankingEvaluationRollupMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordRun() {
        registry.counter("parkio.spa.ranking.evaluation.rollup.runs").increment();
    }

    public void recordSuccess() {
        registry.counter("parkio.spa.ranking.evaluation.rollup.success").increment();
    }

    public void recordFailure(String status) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.rollup.failure",
                        "status",
                        sanitize(status))
                .increment();
    }

    public void recordSkippedOverlap() {
        registry.counter("parkio.spa.ranking.evaluation.rollup.skipped_overlap").increment();
    }

    public void recordProcessed(long evaluations, long outcomes, long rows) {
        if (evaluations > 0) {
            registry.counter("parkio.spa.ranking.evaluation.rollup.raw_evaluations_processed")
                    .increment(evaluations);
        }
        if (outcomes > 0) {
            registry.counter("parkio.spa.ranking.evaluation.rollup.outcomes_processed")
                    .increment(outcomes);
        }
        if (rows > 0) {
            registry.counter("parkio.spa.ranking.evaluation.rollup.rollup_rows_upserted")
                    .increment(rows);
        }
        registry.counter("parkio.spa.ranking.evaluation.rollup.rows_processed").increment(evaluations + outcomes);
    }

    public void recordCleanupDeleted(long count) {
        if (count > 0) {
            registry.counter("parkio.spa.ranking.evaluation.rollup.cleanup.deleted").increment(count);
        }
    }

    public void recordDuration(long millis) {
        Timer.builder("parkio.spa.ranking.evaluation.rollup.duration")
                .register(registry)
                .record(Math.max(0L, millis), TimeUnit.MILLISECONDS);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
