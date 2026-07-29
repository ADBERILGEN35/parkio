package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.RewardShadowObserverPort;
import com.parkio.parking.application.reward.RewardShadowFailureStage;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.reward.RewardContribution;
import com.parkio.parking.reward.RewardEvaluation;
import com.parkio.parking.reward.RewardReplayComparison;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RewardShadowMetrics implements RewardShadowObserverPort {

    private final MeterRegistry registry;

    public RewardShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordOutcomeReceived() {
        registry.counter("parkio.parking.reward.outcome.received").increment();
    }

    @Override
    public void recordContributionProduced(RewardContribution contribution) {
        registry.counter("parkio.parking.reward.contribution.produced", contributionTags(contribution)).increment();
    }

    @Override
    public void recordContributionSkipped(RewardContribution contribution) {
        registry.counter(
                "parkio.parking.reward.contribution.skipped",
                contributionTags(contribution).and("eligibility", contribution.eligibility().name()))
                .increment();
    }

    @Override
    public void recordEvaluationSuccess(RewardEvaluation evaluation, Duration duration) {
        registry.counter(
                "parkio.parking.reward.evaluation.success",
                contributionTags(evaluation.contribution())
                        .and("disposition", evaluation.disposition().name())
                        .and("reward_unit", evaluation.rewardUnit().name())
                        .and("amount_band", amountBand(evaluation.amount().value())))
                .increment();
        registry.counter(
                "parkio.parking.reward.disposition",
                Tags.of("disposition", evaluation.disposition().name()))
                .increment();
        registry.timer("parkio.parking.reward.evaluation.duration").record(duration);
    }

    @Override
    public void recordEvaluationDuplicate(RewardContribution contribution) {
        registry.counter("parkio.parking.reward.evaluation.duplicate", contributionTags(contribution)).increment();
    }

    @Override
    public void recordEvaluationFailure(RewardShadowFailureStage stage, RewardContribution contribution) {
        registry.counter(
                "parkio.parking.reward.evaluation.failure",
                contributionTags(contribution).and("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordReplaySuccess(RewardReplayComparison comparison) {
        registry.counter("parkio.parking.reward.replay.success").increment();
    }

    @Override
    public void recordReplayMismatch(RewardReplayComparison comparison) {
        registry.counter("parkio.parking.reward.replay.mismatch").increment();
    }

    @Override
    public void recordReplayFailure() {
        registry.counter("parkio.parking.reward.replay.failure").increment();
    }

    @Override
    public void recordSchedulerCandidates(int count) {
        registry.summary("parkio.parking.reward.scheduler.candidates").record(count);
    }

    @Override
    public void recordSchedulerCompleted(int count) {
        registry.counter("parkio.parking.reward.scheduler.completed").increment(count);
    }

    @Override
    public void recordSchedulerFailed() {
        registry.counter("parkio.parking.reward.scheduler.failed").increment();
    }

    @Override
    public void recordProcessingResult(RewardShadowProcessingResult result) {
        registry.counter("parkio.parking.reward.processing.result", Tags.of("status", result.status().name())).increment();
    }

    private static Tags contributionTags(RewardContribution contribution) {
        return Tags.of(
                "policy_version", "reward-policy-v1",
                "snapshot_schema_version", "reward-snapshot-v1",
                "attribution_version", contribution.attributionMappingVersion(),
                "subject_type", contribution.subject().type().name(),
                "contribution_role", contribution.contributionRole().name(),
                "attribution_quality", contribution.attributionQuality().name(),
                "outcome_classification", contribution.outcomeClassification().name(),
                "outcome_confidence_band", contribution.outcomeConfidenceBand(),
                "eligibility", contribution.eligibility().name());
    }

    private static String amountBand(int amount) {
        if (amount == 0) {
            return "ZERO";
        }
        if (amount <= 5) {
            return "VERY_LOW";
        }
        if (amount <= 10) {
            return "LOW";
        }
        if (amount <= 15) {
            return "MEDIUM";
        }
        if (amount < 20) {
            return "HIGH";
        }
        return "MAXIMUM";
    }
}
