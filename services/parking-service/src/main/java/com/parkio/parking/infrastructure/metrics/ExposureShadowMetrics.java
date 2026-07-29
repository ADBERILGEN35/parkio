package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.exposure.ExposureShadowFailureStage;
import com.parkio.parking.application.port.ExposureShadowObserverPort;
import com.parkio.parking.exposure.ExposureComparison;
import com.parkio.parking.exposure.ExposureEvaluation;
import com.parkio.parking.exposure.ExposureReplayComparison;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class ExposureShadowMetrics implements ExposureShadowObserverPort {

    private final MeterRegistry registry;

    public ExposureShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordRequestReceived() {
        registry.counter("parkio.parking.exposure.request.received").increment();
    }

    @Override
    public void recordRequestSampled() {
        registry.counter("parkio.parking.exposure.request.sampled").increment();
    }

    @Override
    public void recordRequestSkipped(String reason) {
        registry.counter("parkio.parking.exposure.request.skipped", Tags.of("skip_reason", reason)).increment();
    }

    @Override
    public void recordCandidateEvaluated(ExposureEvaluation evaluation) {
        registry.counter(
                "parkio.parking.exposure.candidate.evaluated",
                Tags.of(
                        "policy_version", evaluation.policyVersion(),
                        "eligibility", evaluation.eligibility().name(),
                        "disposition", evaluation.disposition().name(),
                        "score_band", scoreBand(evaluation.score().total()),
                        "availability_state", evaluation.evidence().availabilityState().name(),
                        "freshness_band", evaluation.evidence().freshnessBand(),
                        "distance_band", evaluation.evidence().distanceBand(),
                        "vehicle_match", evaluation.evidence().vehicleMatch().name(),
                        "trust_level", evaluation.evidence().trustLevel().name()))
                .increment();
    }

    @Override
    public void recordEvaluationSuccess(ExposureComparison comparison, Duration duration) {
        registry.counter(
                "parkio.parking.exposure.evaluation.success",
                Tags.of(
                        "policy_version", comparison.policyVersion(),
                        "search_type", comparison.queryContext().searchType(),
                        "same_top1", Boolean.toString(comparison.sameTop1()),
                        "movement_band", comparison.movementBand(),
                        "candidate_count_band", candidateCountBand(comparison.candidateCount())))
                .increment();
        registry.counter(
                "parkio.parking.exposure.disposition.summary",
                Tags.of("same_top1", Boolean.toString(comparison.sameTop1())))
                .increment();
        registry.counter(
                "parkio.parking.exposure.rank.same_top3",
                Tags.of("same_order", Boolean.toString(comparison.sameTop3Order())))
                .increment();
        registry.timer("parkio.parking.exposure.evaluation.duration").record(duration);
        registry.summary("parkio.parking.exposure.promoted.count").record(comparison.promotedCount());
        registry.summary("parkio.parking.exposure.demoted.count").record(comparison.demotedCount());
    }

    @Override
    public void recordEvaluationFailure(ExposureShadowFailureStage stage) {
        registry.counter(
                "parkio.parking.exposure.evaluation.failure",
                Tags.of("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordTimeBudgetExceeded() {
        registry.counter("parkio.parking.exposure.time_budget.exceeded").increment();
    }

    @Override
    public void recordReplaySuccess(ExposureReplayComparison comparison) {
        registry.counter("parkio.parking.exposure.replay.success").increment();
    }

    @Override
    public void recordReplayMismatch(ExposureReplayComparison comparison) {
        registry.counter("parkio.parking.exposure.replay.mismatch").increment();
    }

    @Override
    public void recordReplayFailure() {
        registry.counter("parkio.parking.exposure.replay.failure").increment();
    }

    private static String scoreBand(int score) {
        if (score >= 7_500) {
            return "VERY_HIGH";
        }
        if (score >= 5_000) {
            return "HIGH";
        }
        if (score >= 3_000) {
            return "MEDIUM";
        }
        if (score >= 1_000) {
            return "LOW";
        }
        return "VERY_LOW";
    }

    private static String candidateCountBand(int count) {
        if (count == 0) {
            return "ZERO";
        }
        if (count <= 3) {
            return "SMALL";
        }
        if (count <= 10) {
            return "MEDIUM";
        }
        return "LARGE";
    }
}
