package com.parkio.parking.application.recommendation.ranking.evaluation;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality ranking evaluation correlation metrics under
 * {@code parkio.spa.ranking.evaluation.*}. Never labels evaluationId.
 */
@Component
public class RankingEvaluationMetrics {

    private final MeterRegistry registry;

    public RankingEvaluationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordCreated() {
        registry.counter("parkio.spa.ranking.evaluation.created").increment();
    }

    public void recordPersistenceFailed(String status) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.persistence_failed",
                        "status",
                        sanitize(status))
                .increment();
    }

    public void recordOutcomeRecorded(String outcomeType, String platform) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.outcome.recorded",
                        "outcomeType",
                        sanitize(outcomeType),
                        "platform",
                        sanitize(platform))
                .increment();
    }

    public void recordOutcomeDuplicate(String outcomeType) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.outcome.duplicate",
                        "outcomeType",
                        sanitize(outcomeType))
                .increment();
    }

    public void recordOutcomePersistenceFailed(String status) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.outcome.persistence_failed",
                        "status",
                        sanitize(status))
                .increment();
    }

    public void recordOutcomeExpired() {
        registry.counter("parkio.spa.ranking.evaluation.outcome.expired").increment();
    }

    public void recordOutcomeRejected(String status) {
        registry.counter(
                        "parkio.spa.ranking.evaluation.outcome.rejected",
                        "status",
                        sanitize(status))
                .increment();
    }

    public void recordCleanupDeleted(long count) {
        if (count <= 0) {
            return;
        }
        registry.counter("parkio.spa.ranking.evaluation.cleanup.deleted").increment(count);
    }

    public void recordShadowOrderUpdated() {
        registry.counter("parkio.spa.ranking.evaluation.shadow_order_updated").increment();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
