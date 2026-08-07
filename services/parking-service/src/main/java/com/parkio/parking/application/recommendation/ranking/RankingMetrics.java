package com.parkio.parking.application.recommendation.ranking;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality ranking metrics. Never labels user IDs, candidate IDs,
 * coordinates, titles, or favourite IDs.
 */
@Component
public class RankingMetrics {

    private final MeterRegistry registry;

    public RankingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordApplied(RankingVersion version, RankingStatus status, long durationNanos) {
        registry.counter(
                        "parkio.spa.ranking.applied",
                        "version",
                        version.name(),
                        "status",
                        status.name())
                .increment();
        Timer.builder("parkio.spa.ranking.duration")
                .tag("status", status.name())
                .publishPercentileHistogram(false)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        if (status == RankingStatus.FALLBACK) {
            registry.counter("parkio.spa.ranking.fallback").increment();
        }
    }

    public void recordFavouriteLookup(boolean success, long durationNanos) {
        registry.counter(
                        "parkio.spa.ranking.favourite_lookup",
                        "outcome",
                        success ? "ok" : "failure")
                .increment();
        Timer.builder("parkio.spa.ranking.favourite_lookup.duration")
                .tag("outcome", success ? "ok" : "failure")
                .publishPercentileHistogram(false)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordTopChannel(String channel) {
        registry.counter("parkio.spa.ranking.top_channel", "channel", channel).increment();
    }

    public void recordScoreBucket(double score) {
        registry.counter("parkio.spa.ranking.score_bucket", "bucket", scoreBucket(score)).increment();
    }

    /**
     * SPA-06 distance-baseline vs deterministic comparison.
     *
     * <p>Writes {@code parkio.spa.ranking.baseline.*} only. Legacy
     * {@code parkio.spa.ranking.shadow.top1_changed} / {@code …top3_overlap} names
     * collided with SPA-14 challenger metrics and are no longer emitted here.
     */
    public void recordShadow(boolean top1Changed, int top3Overlap) {
        registry.counter(
                        "parkio.spa.ranking.baseline.top1_changed",
                        "changed",
                        Boolean.toString(top1Changed))
                .increment();
        registry.counter(
                        "parkio.spa.ranking.baseline.top3_overlap",
                        "overlap",
                        Integer.toString(Math.min(Math.max(top3Overlap, 0), 3)))
                .increment();
    }

    public void recordMissingFactor(String factor) {
        registry.counter("parkio.spa.ranking.factor_missing", "factor", factor).increment();
    }

    static String scoreBucket(double score) {
        if (!Double.isFinite(score) || score < 0.25) {
            return "0_25";
        }
        if (score < 0.5) {
            return "25_50";
        }
        if (score < 0.75) {
            return "50_75";
        }
        return "75_100";
    }
}
