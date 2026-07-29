package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.port.RewardLedgerPort;
import com.parkio.parking.application.port.RewardShadowObserverPort;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardSubject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RewardShadowApplicationServiceTest {

    @Test
    void appendsPendingRewardIntentForEligibleReporterOutcome() {
        RecordingLedger ledger = new RecordingLedger(false);
        RewardShadowApplicationService service = new RewardShadowApplicationService(
                ledger,
                RewardShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        RewardShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95));

        assertThat(result.status()).isEqualTo(RewardShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries).hasSize(1);
        assertThat(ledger.entries.getFirst().calculatedAmount().value()).isGreaterThan(0);
    }

    @Test
    void duplicateLedgerWriteIsReportedIdempotently() {
        RewardShadowApplicationService service = new RewardShadowApplicationService(
                new RecordingLedger(true),
                RewardShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        RewardShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95));

        assertThat(result.status()).isEqualTo(RewardShadowProcessingResult.Status.DUPLICATE);
    }

    @Test
    void expiredWithoutEvidenceProducesNoRewardIntentAmount() {
        RecordingLedger ledger = new RecordingLedger(false);
        RewardShadowApplicationService service = new RewardShadowApplicationService(
                ledger,
                RewardShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        RewardShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE,
                OutcomeReason.TIME_EXPIRED_NO_EVIDENCE,
                30));

        assertThat(result.status()).isEqualTo(RewardShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries.getFirst().calculatedAmount().isZero()).isTrue();
    }

    private static ValidatedOutcomeForReward candidate(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        return new ValidatedOutcomeForReward(outcome(classification, reason, confidence), UUID.randomUUID());
    }

    private static OutcomeHistoryRecord outcome(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        Instant publishedAt = Instant.parse("2026-07-28T09:00:00Z");
        Instant evaluatedAt = Instant.parse("2026-07-28T10:00:00Z");
        UUID spotId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        OutcomePolicyVersion policyVersion = OutcomePolicyVersion.of("outcome-policy-v1");
        OutcomeTimeline timeline = OutcomeTimeline.of(publishedAt, publishedAt.plus(Duration.ofMinutes(10)), List.of());
        OutcomeEvaluation evaluation = new OutcomeEvaluation(
                spotId,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                Set.of(reason),
                timeline,
                Duration.ofMinutes(60),
                false,
                policyVersion,
                evaluatedAt);
        OutcomeSnapshot snapshot = new OutcomeSnapshot(
                new OutcomeEvidence(
                        spotId,
                        ParkingSpotStatus.ACTIVE,
                        publishedAt.minusSeconds(30),
                        publishedAt,
                        publishedAt.plus(Duration.ofMinutes(10)),
                        evaluatedAt,
                        1,
                        0,
                        0.9,
                        timeline),
                new OutcomeEvaluationContext(evaluatedAt, policyVersion, Duration.ofMinutes(10)),
                evaluation);
        return new OutcomeHistoryRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spotId,
                policyVersion,
                "outcome-snapshot-v1",
                OutcomeEvaluationTrigger.PUBLICATION,
                UUID.randomUUID(),
                evaluatedAt,
                evaluatedAt,
                snapshot,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                false,
                evaluatedAt);
    }

    private static final class RecordingLedger implements RewardLedgerPort {
        private final boolean duplicate;
        private final List<PendingRewardIntent> entries = new ArrayList<>();

        private RecordingLedger(boolean duplicate) {
            this.duplicate = duplicate;
        }

        @Override
        public void append(PendingRewardIntent intent) {
            if (duplicate) {
                throw new DuplicatePendingRewardIntentException("duplicate");
            }
            entries.add(intent);
        }

        @Override
        public Optional<PendingRewardIntent> findByEvaluationId(UUID evaluationId) {
            return entries.stream().filter(entry -> entry.evaluationId().equals(evaluationId)).findFirst();
        }

        @Override
        public List<PendingRewardIntent> findBySubject(RewardSubject subject) {
            return entries.stream().filter(entry -> entry.rewardSubject().equals(subject)).toList();
        }

        @Override
        public Optional<PendingRewardIntent> findLatestForContribution(UUID sourceContributionId) {
            return entries.stream().filter(entry -> entry.sourceContributionId().equals(sourceContributionId)).findFirst();
        }
    }
}
