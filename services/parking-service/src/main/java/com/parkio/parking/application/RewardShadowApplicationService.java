package com.parkio.parking.application;

import com.parkio.parking.application.port.RewardLedgerPort;
import com.parkio.parking.application.port.RewardShadowObserverPort;
import com.parkio.parking.application.reward.RewardShadowFailureStage;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardContribution;
import com.parkio.parking.reward.RewardEngine;
import com.parkio.parking.reward.RewardEvaluation;
import com.parkio.parking.reward.RewardEvaluationContext;
import com.parkio.parking.reward.RewardPolicyConfig;
import com.parkio.parking.reward.RewardReplayer;
import com.parkio.parking.reward.RewardSnapshotSchemaVersion;
import com.parkio.parking.reward.ValidatedRewardContributionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RewardShadowApplicationService {

    private final RewardLedgerPort ledger;
    private final RewardShadowObserverPort observer;
    private final Clock clock;
    private final RewardEngine engine = new RewardEngine();
    private final RewardReplayer replayer = new RewardReplayer();

    public RewardShadowApplicationService(
            RewardLedgerPort ledger,
            RewardShadowObserverPort observer,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RewardShadowProcessingResult process(ValidatedOutcomeForReward candidate) {
        Objects.requireNonNull(candidate, "candidate");
        observer.recordOutcomeReceived();
        long started = System.nanoTime();
        RewardContribution contribution = null;
        try {
            contribution = ValidatedRewardContributionFactory.reporterContribution(
                    candidate.outcomeRecord(),
                    candidate.reporterUserId());
            observer.recordContributionProduced(contribution);
            RewardEvaluation evaluation = engine.evaluate(
                    contribution,
                    new RewardEvaluationContext(
                            candidate.outcomeRecord().evaluatedAt(),
                            RewardPolicyConfig.POLICY_VERSION,
                            RewardSnapshotSchemaVersion.V1));
            PendingRewardIntent intent = new PendingRewardIntent(
                    deterministicId("pending-reward-intent|" + contribution.contributionId() + "|" + RewardPolicyConfig.POLICY_VERSION),
                    deterministicId("pending-reward-evaluation|" + contribution.contributionId() + "|" + RewardPolicyConfig.POLICY_VERSION),
                    contribution.subject(),
                    contribution.contributionRole(),
                    contribution.sourceOutcomeRecordId(),
                    contribution.contributionId(),
                    contribution.sourceParkingSpotId(),
                    contribution.evidenceGroupId(),
                    RewardPolicyConfig.POLICY_VERSION,
                    contribution.attributionMappingVersion(),
                    RewardSnapshotSchemaVersion.V1,
                    evaluation.disposition(),
                    evaluation.rewardUnit(),
                    evaluation.amount(),
                    contribution.eligibility(),
                    contribution.primaryEligibilityReason(),
                    contribution.outcomeClassification(),
                    contribution.outcomeConfidenceBand(),
                    evaluation.evaluatedAt(),
                    candidate.outcomeRecord().evidenceCutoffAt(),
                    clock.instant(),
                    contribution,
                    evaluation);
            ledger.append(intent);
            Duration duration = Duration.ofNanos(System.nanoTime() - started);
            observer.recordEvaluationSuccess(evaluation, duration);
            var replay = replayer.replay(intent);
            if (replay.identical()) {
                observer.recordReplaySuccess(replay);
            } else {
                observer.recordReplayMismatch(replay);
            }
            return RewardShadowProcessingResult.appended(candidate.outcomeRecord().recordId());
        } catch (DuplicatePendingRewardIntentException ex) {
            if (contribution != null) {
                observer.recordEvaluationDuplicate(contribution);
            }
            return RewardShadowProcessingResult.duplicate(candidate.outcomeRecord().recordId());
        } catch (RuntimeException ex) {
            RewardShadowFailureStage stage = classifyFailure(ex);
            if (contribution != null) {
                observer.recordEvaluationFailure(stage, contribution);
            } else {
                observer.recordReplayFailure();
            }
            return RewardShadowProcessingResult.failed(candidate.outcomeRecord().recordId(), stage);
        }
    }

    private static RewardShadowFailureStage classifyFailure(RuntimeException ex) {
        if (ex instanceof UnsupportedOperationException) {
            return RewardShadowFailureStage.OBSERVABILITY_FAILURE;
        }
        if (ex.getClass().getSimpleName().contains("UnsupportedRewardPolicyVersion")) {
            return RewardShadowFailureStage.POLICY_VERSION_UNSUPPORTED;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return RewardShadowFailureStage.CONTRIBUTION_MAPPING_FAILURE;
        }
        return RewardShadowFailureStage.LEDGER_APPEND_FAILURE;
    }

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
