package com.parkio.parking.reward;

import com.parkio.parking.outcome.OutcomeClassification;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable append-only pending reward ledger row. */
public record PendingRewardIntent(
        UUID rewardIntentId,
        UUID evaluationId,
        RewardSubject rewardSubject,
        RewardContribution.ContributionRole contributionRole,
        UUID sourceOutcomeRecordId,
        UUID sourceContributionId,
        UUID sourceParkingSpotId,
        UUID evidenceGroupId,
        String rewardPolicyVersion,
        String attributionMappingVersion,
        RewardSnapshotSchemaVersion snapshotSchemaVersion,
        RewardEvaluation.Disposition disposition,
        RewardUnit rewardUnit,
        RewardAmount calculatedAmount,
        RewardContribution.Eligibility eligibility,
        RewardContribution.EligibilityReason primaryReason,
        OutcomeClassification outcomeClassification,
        String outcomeConfidenceBand,
        Instant evaluatedAt,
        Instant evidenceCutoffAt,
        Instant createdAt,
        RewardContribution contribution,
        RewardEvaluation evaluation) {

    public PendingRewardIntent {
        Objects.requireNonNull(rewardIntentId, "rewardIntentId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(rewardSubject, "rewardSubject");
        Objects.requireNonNull(contributionRole, "contributionRole");
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(sourceContributionId, "sourceContributionId");
        Objects.requireNonNull(sourceParkingSpotId, "sourceParkingSpotId");
        Objects.requireNonNull(evidenceGroupId, "evidenceGroupId");
        Objects.requireNonNull(rewardPolicyVersion, "rewardPolicyVersion");
        Objects.requireNonNull(attributionMappingVersion, "attributionMappingVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(rewardUnit, "rewardUnit");
        Objects.requireNonNull(calculatedAmount, "calculatedAmount");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(primaryReason, "primaryReason");
        Objects.requireNonNull(outcomeClassification, "outcomeClassification");
        Objects.requireNonNull(outcomeConfidenceBand, "outcomeConfidenceBand");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(evidenceCutoffAt, "evidenceCutoffAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(evaluation, "evaluation");
    }
}
