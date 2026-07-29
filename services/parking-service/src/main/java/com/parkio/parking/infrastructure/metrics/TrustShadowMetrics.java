package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.trust.TrustEvaluation;
import com.parkio.parking.trust.TrustEvidence;
import com.parkio.parking.trust.TrustReplayComparison;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class TrustShadowMetrics implements TrustShadowObserverPort {

    private final MeterRegistry registry;

    public TrustShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordOutcomeReceived() {
        registry.counter("parkio.parking.trust.outcome.received").increment();
    }

    @Override
    public void recordEvidenceProduced(TrustEvidence evidence) {
        registry.counter("parkio.parking.trust.evidence.produced", evidenceTags(evidence)).increment();
    }

    @Override
    public void recordEvidenceSkipped(TrustEvidence evidence) {
        registry.counter(
                "parkio.parking.trust.evidence.skipped",
                evidenceTags(evidence).and("eligibility_result", evidence.eligibility().name()))
                .increment();
    }

    @Override
    public void recordUpdateSuccess(TrustEvaluation evaluation, Duration duration) {
        registry.counter(
                "parkio.parking.trust.update.success",
                evidenceTags(evaluation.evidence())
                        .and("update_direction", evaluation.direction().name())
                        .and("trust_level", evaluation.resultingSnapshot().level().name())
                        .and("score_band", scoreBand(evaluation.resultingSnapshot().score().basisPoints()))
                        .and("confidence_band", confidenceBand(evaluation.resultingSnapshot().confidence().basisPoints())))
                .increment();
        registry.timer("parkio.parking.trust.evaluation.duration").record(duration);
    }

    @Override
    public void recordUpdateDuplicate(TrustEvidence evidence) {
        registry.counter("parkio.parking.trust.update.duplicate", evidenceTags(evidence)).increment();
    }

    @Override
    public void recordUpdateFailure(TrustShadowFailureStage stage, TrustEvidence evidence) {
        registry.counter(
                "parkio.parking.trust.update.failure",
                evidenceTags(evidence).and("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordReplaySuccess(TrustReplayComparison comparison) {
        registry.counter("parkio.parking.trust.replay.success").increment();
    }

    @Override
    public void recordReplayMismatch(TrustReplayComparison comparison) {
        registry.counter("parkio.parking.trust.replay.mismatch").increment();
    }

    @Override
    public void recordReplayFailure() {
        registry.counter("parkio.parking.trust.replay.failure").increment();
    }

    @Override
    public void recordSchedulerCandidates(int count) {
        registry.summary("parkio.parking.trust.scheduler.candidates").record(count);
    }

    @Override
    public void recordSchedulerCompleted(int count) {
        registry.counter("parkio.parking.trust.scheduler.completed").increment(count);
    }

    @Override
    public void recordSchedulerFailed() {
        registry.counter("parkio.parking.trust.scheduler.failed").increment();
    }

    @Override
    public void recordProcessingResult(TrustShadowProcessingResult result) {
        registry.counter("parkio.parking.trust.processing.result", Tags.of("status", result.status().name())).increment();
    }

    private static Tags evidenceTags(TrustEvidence evidence) {
        return Tags.of(
                "policy_version", "trust-policy-v1",
                "snapshot_schema_version", "trust-snapshot-v1",
                "subject_type", evidence.subject().type().name(),
                "trust_domain", evidence.domain().name(),
                "evidence_type", evidence.evidenceType().name(),
                "contribution_role", evidence.contributionRole().name(),
                "attribution_quality", evidence.attributionQuality().name());
    }

    private static String scoreBand(int basisPoints) {
        if (basisPoints < 3_000) {
            return "VERY_LOW";
        }
        if (basisPoints < 4_500) {
            return "LOW";
        }
        if (basisPoints < 5_500) {
            return "NEUTRAL";
        }
        if (basisPoints < 7_000) {
            return "MEDIUM";
        }
        if (basisPoints < 8_500) {
            return "HIGH";
        }
        return "VERY_HIGH";
    }

    private static String confidenceBand(int basisPoints) {
        if (basisPoints < 2_500) {
            return "VERY_LOW";
        }
        if (basisPoints < 5_000) {
            return "LOW";
        }
        if (basisPoints < 7_500) {
            return "MEDIUM";
        }
        if (basisPoints < 9_000) {
            return "HIGH";
        }
        return "VERY_HIGH";
    }
}

