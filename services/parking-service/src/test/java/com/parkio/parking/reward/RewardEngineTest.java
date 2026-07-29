package com.parkio.parking.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RewardEngineTest {

    private final RewardEngine engine = new RewardEngine();

    @Test
    void confirmedCorrectDirectReporterContributionIsPendingRewardable() {
        RewardEvaluation evaluation = engine.evaluate(
                contribution(
                        OutcomeClassification.CONFIRMED_CORRECT,
                        RewardContribution.AttributionQuality.DIRECT,
                        RewardContribution.Eligibility.ELIGIBLE,
                        RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION,
                        95,
                        "HIGH"),
                context("reward-policy-v1"));

        assertThat(evaluation.disposition()).isEqualTo(RewardEvaluation.Disposition.PENDING);
        assertThat(evaluation.amount().value()).isEqualTo(20);
        assertThat(evaluation.rewardUnit()).isEqualTo(RewardUnit.POINTS);
    }

    @Test
    void singleVerificationGetsSmallerRewardThanDirectAttribution() {
        RewardEvaluation strong = engine.evaluate(
                contribution(
                        OutcomeClassification.CONFIRMED_CORRECT,
                        RewardContribution.AttributionQuality.STRONG,
                        RewardContribution.Eligibility.ELIGIBLE,
                        RewardContribution.EligibilityReason.STRONG_SINGLE_VERIFICATION,
                        80,
                        "MEDIUM"),
                context("reward-policy-v1"));
        RewardEvaluation direct = engine.evaluate(
                contribution(
                        OutcomeClassification.CONFIRMED_CORRECT,
                        RewardContribution.AttributionQuality.DIRECT,
                        RewardContribution.Eligibility.ELIGIBLE,
                        RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION,
                        95,
                        "HIGH"),
                context("reward-policy-v1"));

        assertThat(strong.amount().value()).isLessThan(direct.amount().value());
        assertThat(strong.disposition()).isEqualTo(RewardEvaluation.Disposition.PENDING);
    }

    @Test
    void expiredWithoutEvidenceCreatesNoPenalty() {
        RewardEvaluation evaluation = engine.evaluate(
                contribution(
                        OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE,
                        RewardContribution.AttributionQuality.AMBIGUOUS,
                        RewardContribution.Eligibility.AMBIGUOUS_ATTRIBUTION,
                        RewardContribution.EligibilityReason.FINAL_EXPIRED_WITHOUT_EVIDENCE,
                        30,
                        "LOW"),
                context("reward-policy-v1"));

        assertThat(evaluation.disposition()).isEqualTo(RewardEvaluation.Disposition.NO_REWARD);
        assertThat(evaluation.amount().isZero()).isTrue();
    }

    @Test
    void unknownPolicyVersionFailsExplicitly() {
        assertThatThrownBy(() -> engine.evaluate(
                contribution(
                        OutcomeClassification.CONFIRMED_CORRECT,
                        RewardContribution.AttributionQuality.DIRECT,
                        RewardContribution.Eligibility.ELIGIBLE,
                        RewardContribution.EligibilityReason.DIRECT_MULTI_VERIFICATION,
                        95,
                        "HIGH"),
                context("reward-policy-v999")))
                .isInstanceOf(UnsupportedRewardPolicyVersionException.class);
    }

    @Test
    void invalidPolicyConfigFails() {
        assertThatThrownBy(() -> new RewardPolicyConfig(
                "bad",
                10,
                8_000,
                9_000,
                1_000,
                5_000,
                6_000,
                7_000,
                1,
                10)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RewardContribution contribution(
            OutcomeClassification classification,
            RewardContribution.AttributionQuality attributionQuality,
            RewardContribution.Eligibility eligibility,
            RewardContribution.EligibilityReason reason,
            int confidence,
            String confidenceBand) {
        return new RewardContribution(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new RewardSubject(RewardSubject.Type.USER, UUID.randomUUID()),
                RewardContribution.ContributionRole.REPORTER,
                attributionQuality,
                eligibility,
                reason,
                Set.of(reason),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                classification,
                confidence,
                confidenceBand,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                Set.of(OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS),
                Instant.parse("2026-07-28T09:00:00Z"),
                Instant.parse("2026-07-28T10:00:00Z"),
                ValidatedRewardContributionFactory.ATTRIBUTION_MAPPING_VERSION);
    }

    private static RewardEvaluationContext context(String version) {
        return new RewardEvaluationContext(
                Instant.parse("2026-07-28T10:00:00Z"),
                version,
                RewardSnapshotSchemaVersion.V1);
    }
}
