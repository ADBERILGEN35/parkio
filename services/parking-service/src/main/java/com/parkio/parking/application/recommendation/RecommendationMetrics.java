package com.parkio.parking.application.recommendation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality recommendation metrics. Never labels destination, coordinates,
 * facility/spot IDs, titles, or user IDs.
 */
@Component
public class RecommendationMetrics {

    private final MeterRegistry registry;

    public RecommendationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(
            boolean partial,
            boolean bothFailed,
            int communityCount,
            int municipalCount,
            int resultCount,
            int radiusMeters,
            int limit,
            long durationNanos) {
        registry.counter("parkio.spa.recommendations.requests", "partial", Boolean.toString(partial))
                .increment();
        if (bothFailed) {
            registry.counter("parkio.spa.recommendations.both_channel_failures").increment();
        }
        registry.counter("parkio.spa.recommendations.candidates", "channel", "community")
                .increment(communityCount);
        registry.counter("parkio.spa.recommendations.candidates", "channel", "municipal")
                .increment(municipalCount);
        registry.summary("parkio.spa.recommendations.result_count").record(resultCount);
        registry.counter(
                        "parkio.spa.recommendations.radius_bucket",
                        "bucket",
                        radiusBucket(radiusMeters))
                .increment();
        registry.counter(
                        "parkio.spa.recommendations.limit_bucket",
                        "bucket",
                        limitBucket(limit))
                .increment();
        Timer.builder("parkio.spa.recommendations.duration")
                .publishPercentileHistogram(false)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    static String radiusBucket(int radiusMeters) {
        if (radiusMeters <= 500) {
            return "0_500";
        }
        if (radiusMeters <= 1500) {
            return "501_1500";
        }
        if (radiusMeters <= 3000) {
            return "1501_3000";
        }
        return "3001_5000";
    }

    static String limitBucket(int limit) {
        if (limit <= 10) {
            return "1_10";
        }
        if (limit <= 25) {
            return "11_25";
        }
        return "26_50";
    }
}
