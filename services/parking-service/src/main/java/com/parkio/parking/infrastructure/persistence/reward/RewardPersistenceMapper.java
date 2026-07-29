package com.parkio.parking.infrastructure.persistence.reward;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.parkio.parking.infrastructure.persistence.entity.PendingRewardLedgerEntity;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardAmount;
import com.parkio.parking.reward.RewardContribution;
import com.parkio.parking.reward.RewardEvaluation;
import com.parkio.parking.reward.RewardSnapshotSchemaVersion;
import com.parkio.parking.reward.RewardSubject;
import com.parkio.parking.reward.RewardUnit;

public final class RewardPersistenceMapper {

    private final ObjectMapper objectMapper;

    public RewardPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public PendingRewardLedgerEntity toEntity(PendingRewardIntent intent) {
        try {
            return new PendingRewardLedgerEntity(
                    intent.rewardIntentId(),
                    intent.evaluationId(),
                    intent.rewardSubject().type().name(),
                    intent.rewardSubject().subjectId(),
                    intent.contributionRole().name(),
                    intent.sourceOutcomeRecordId(),
                    intent.sourceContributionId(),
                    intent.sourceParkingSpotId(),
                    intent.evidenceGroupId(),
                    intent.rewardPolicyVersion(),
                    intent.attributionMappingVersion(),
                    intent.snapshotSchemaVersion().toString(),
                    intent.disposition().name(),
                    intent.rewardUnit().name(),
                    intent.calculatedAmount().value(),
                    intent.eligibility().name(),
                    intent.primaryReason().name(),
                    intent.outcomeClassification().name(),
                    intent.outcomeConfidenceBand(),
                    intent.evaluatedAt(),
                    intent.evidenceCutoffAt(),
                    objectMapper.writeValueAsString(intent.contribution()),
                    objectMapper.writeValueAsString(intent.evaluation()),
                    intent.createdAt());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize pending reward intent", ex);
        }
    }

    public PendingRewardIntent toDomain(PendingRewardLedgerEntity entity) {
        try {
            RewardContribution contribution =
                    objectMapper.readValue(entity.getContributionJson(), RewardContribution.class);
            RewardEvaluation evaluation =
                    objectMapper.readValue(entity.getEvaluationJson(), RewardEvaluation.class);
            return new PendingRewardIntent(
                    entity.getId(),
                    entity.getEvaluationId(),
                    new RewardSubject(
                            RewardSubject.Type.valueOf(entity.getRewardSubjectType()),
                            entity.getRewardSubjectId()),
                    RewardContribution.ContributionRole.valueOf(entity.getContributionRole()),
                    entity.getSourceOutcomeRecordId(),
                    entity.getSourceContributionId(),
                    entity.getSourceParkingSpotId(),
                    entity.getEvidenceGroupId(),
                    entity.getRewardPolicyVersion(),
                    entity.getAttributionMappingVersion(),
                    RewardSnapshotSchemaVersion.V1,
                    RewardEvaluation.Disposition.valueOf(entity.getDisposition()),
                    RewardUnit.valueOf(entity.getRewardUnit()),
                    new RewardAmount(entity.getCalculatedAmount()),
                    RewardContribution.Eligibility.valueOf(entity.getEligibility()),
                    RewardContribution.EligibilityReason.valueOf(entity.getPrimaryReason()),
                    OutcomeClassification.valueOf(entity.getOutcomeClassification()),
                    entity.getOutcomeConfidenceBand(),
                    entity.getEvaluatedAt(),
                    entity.getEvidenceCutoffAt(),
                    entity.getCreatedAt(),
                    contribution,
                    evaluation);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize pending reward intent", ex);
        }
    }
}
