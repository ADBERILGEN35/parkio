package com.parkio.parking.application.recommendation.ranking.shadow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality shadow ranking metrics under {@code parkio.spa.ranking.shadow.*}.
 * Never labels user IDs, candidate IDs, coordinates, or titles.
 */
@Component
public class ShadowRankingMetrics {

    private final MeterRegistry registry;

    public ShadowRankingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordRequest() {
        registry.counter("parkio.spa.ranking.shadow.requests").increment();
    }

    public void recordSampled() {
        registry.counter("parkio.spa.ranking.shadow.sampled").increment();
    }

    public void recordSuccess() {
        registry.counter("parkio.spa.ranking.shadow.success").increment();
    }

    public void recordSkipped(String reason) {
        registry.counter("parkio.spa.ranking.shadow.skipped", "reason", sanitizeReason(reason)).increment();
    }

    public void recordTimeout() {
        registry.counter("parkio.spa.ranking.shadow.timeout").increment();
    }

    public void recordProviderError() {
        registry.counter("parkio.spa.ranking.shadow.provider_error").increment();
    }

    public void recordInvalidOutput() {
        registry.counter("parkio.spa.ranking.shadow.invalid_output").increment();
    }

    public void recordCircuitOpen() {
        registry.counter("parkio.spa.ranking.shadow.circuit_open").increment();
    }

    public void recordDuration(long durationNanos) {
        Timer.builder("parkio.spa.ranking.shadow.duration")
                .publishPercentileHistogram(false)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordComparison(ShadowComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        registry.counter(
                        "parkio.spa.ranking.shadow.top1_agreement",
                        "agreed",
                        Boolean.toString(comparison.top1Agreement()))
                .increment();
        registry.counter(
                        "parkio.spa.ranking.shadow.top3_overlap",
                        "overlap",
                        Integer.toString(comparison.top3Overlap()))
                .increment();
        registry.counter(
                        "parkio.spa.ranking.shadow.rank_correlation_bucket",
                        "bucket",
                        correlationBucket(comparison.spearmanRankCorrelation()))
                .increment();
        registry.counter(
                        "parkio.spa.ranking.shadow.mean_rank_delta_bucket",
                        "bucket",
                        meanDeltaBucket(comparison.meanAbsoluteRankDelta()))
                .increment();
    }

    static String correlationBucket(double spearman) {
        if (!Double.isFinite(spearman)) {
            return "unknown";
        }
        if (spearman >= 0.8) {
            return "high";
        }
        if (spearman >= 0.4) {
            return "mid";
        }
        if (spearman >= 0.0) {
            return "low";
        }
        return "neg";
    }

    static String meanDeltaBucket(double meanDelta) {
        if (!Double.isFinite(meanDelta) || meanDelta < 0.5) {
            return "0";
        }
        if (meanDelta < 1.5) {
            return "1";
        }
        if (meanDelta < 3.0) {
            return "2_3";
        }
        return "3_plus";
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason;
    }
}
