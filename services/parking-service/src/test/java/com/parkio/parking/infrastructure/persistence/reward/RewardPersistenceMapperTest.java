package com.parkio.parking.infrastructure.persistence.reward;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardAmount;
import com.parkio.parking.reward.RewardContribution;
import com.parkio.parking.reward.RewardEvaluation;
import com.parkio.parking.reward.RewardSubject;
import com.parkio.parking.reward.RewardSnapshotSchemaVersion;
import com.parkio.parking.reward.RewardUnit;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RewardPersistenceMapperTest {

    private final RewardPersistenceMapper mapper =
            new RewardPersistenceMapper(JsonMapper.builder().findAndAddModules().build());

    @Test
    void serializesAndDeserializesIntentStably() {
        RewardContribution contribution = new RewardContribution(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new RewardSubject(RewardSubject.Type.USER, UUID.randomUUID()),
                RewardContribution.ContributionRole.REPORTER,
                RewardContribution.AttributionQuality.DIRECT,
                RewardContribution.Eligibility.ELIGIBLE,
                RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION,
                Set.of(RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                95,
                "HIGH",
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                Set.of(OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS),
                Instant.parse("2026-07-28T09:00:00Z"),
                Instant.parse("2026-07-28T10:00:00Z"),
                "reward-attribution-v1");
        RewardEvaluation evaluation = new RewardEvaluation(
                contribution,
                RewardEvaluation.Disposition.PENDING,
                new RewardAmount(20),
                RewardUnit.POINTS,
                "PENDING_DIRECT_MULTI_VERIFICATION",
                Set.of(RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION),
                "reward-policy-v1",
                Instant.parse("2026-07-28T10:00:00Z"));
        PendingRewardIntent intent = new PendingRewardIntent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                contribution.subject(),
                contribution.contributionRole(),
                contribution.sourceOutcomeRecordId(),
                contribution.contributionId(),
                contribution.sourceParkingSpotId(),
                contribution.evidenceGroupId(),
                "reward-policy-v1",
                "reward-attribution-v1",
                RewardSnapshotSchemaVersion.V1,
                evaluation.disposition(),
                evaluation.rewardUnit(),
                evaluation.amount(),
                contribution.eligibility(),
                contribution.primaryEligibilityReason(),
                contribution.outcomeClassification(),
                contribution.outcomeConfidenceBand(),
                evaluation.evaluatedAt(),
                Instant.parse("2026-07-28T10:00:00Z"),
                Instant.parse("2026-07-28T10:00:01Z"),
                contribution,
                evaluation);

        PendingRewardIntent restored = mapper.toDomain(mapper.toEntity(intent));

        assertThat(restored).isEqualTo(intent);
    }
}
