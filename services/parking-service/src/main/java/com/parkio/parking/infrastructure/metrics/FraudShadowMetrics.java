package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.fraud.FraudShadowFailureStage;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.port.FraudShadowObserverPort;
import com.parkio.parking.fraud.FraudEvaluation;
import com.parkio.parking.fraud.FraudFeatureVector;
import com.parkio.parking.fraud.FraudReplayComparison;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class FraudShadowMetrics implements FraudShadowObserverPort {

    private final MeterRegistry registry;

    public FraudShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordCandidateReceived() {
        registry.counter("parkio.parking.fraud.candidate.received").increment();
    }

    @Override
    public void recordFeatureVectorProduced(FraudFeatureVector features) {
        registry.counter("parkio.parking.fraud.feature.produced", featureTags(features)).increment();
    }

    @Override
    public void recordCandidateSkipped(FraudFeatureVector features) {
        registry.counter("parkio.parking.fraud.candidate.skipped", featureTags(features)).increment();
    }

    @Override
    public void recordEvaluationSuccess(FraudEvaluation evaluation, Duration duration) {
        registry.counter(
                "parkio.parking.fraud.evaluation.success",
                featureTags(evaluation)
                        .and("risk_band", evaluation.riskBand().name())
                        .and("confidence_band", evaluation.confidenceBand().name())
                        .and("disposition", evaluation.disposition().name())
                        .and("evidence_volume_band", evidenceVolumeBand(evaluation.evidenceVolume().count())))
                .increment();
        registry.timer("parkio.parking.fraud.evaluation.duration").record(duration);
    }

    @Override
    public void recordEvaluationDuplicate(FraudFeatureVector features) {
        registry.counter("parkio.parking.fraud.evaluation.duplicate", featureTags(features)).increment();
    }

    @Override
    public void recordEvaluationFailure(FraudShadowFailureStage stage, FraudFeatureVector features) {
        registry.counter(
                "parkio.parking.fraud.evaluation.failure",
                featureTags(features).and("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordReplaySuccess(FraudReplayComparison comparison) {
        registry.counter("parkio.parking.fraud.replay.success").increment();
    }

    @Override
    public void recordReplayMismatch(FraudReplayComparison comparison) {
        registry.counter("parkio.parking.fraud.replay.mismatch").increment();
    }

    @Override
    public void recordReplayFailure() {
        registry.counter("parkio.parking.fraud.replay.failure").increment();
    }

    @Override
    public void recordSchedulerCandidates(int count) {
        registry.summary("parkio.parking.fraud.scheduler.candidates").record(count);
    }

    @Override
    public void recordSchedulerCompleted(int count) {
        registry.counter("parkio.parking.fraud.scheduler.completed").increment(count);
    }

    @Override
    public void recordSchedulerFailed() {
        registry.counter("parkio.parking.fraud.scheduler.failed").increment();
    }

    @Override
    public void recordProcessingResult(FraudShadowProcessingResult result) {
        registry.counter("parkio.parking.fraud.processing.result", Tags.of("status", result.status().name())).increment();
    }

    private static Tags featureTags(FraudFeatureVector features) {
        return Tags.of(
                "policy_version", "fraud-policy-v1",
                "schema_version", "fraud-snapshot-v1",
                "mapping_version", "fraud-mapping-v1",
                "aggregation_version", features.aggregationVersion(),
                "subject_type", features.subject().type().name(),
                "fraud_domain", features.domain().name());
    }

    private static Tags featureTags(FraudEvaluation evaluation) {
        return Tags.of(
                "policy_version", evaluation.policyVersion(),
                "schema_version", "fraud-snapshot-v1",
                "mapping_version", "fraud-mapping-v1",
                "aggregation_version", "fraud-aggregation-v1",
                "subject_type", evaluation.subject().type().name(),
                "fraud_domain", evaluation.domain().name());
    }

    private static String evidenceVolumeBand(int count) {
        if (count <= 0) {
            return "NONE";
        }
        if (count == 1) {
            return "ONE";
        }
        if (count <= 3) {
            return "FEW";
        }
        if (count <= 10) {
            return "MODERATE";
        }
        return "MANY";
    }
}
