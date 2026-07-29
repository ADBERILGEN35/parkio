package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.fraud.FraudReporterOutcomeAggregate;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import com.parkio.parking.application.port.FraudLedgerPort;
import com.parkio.parking.application.port.FraudReporterOutcomeAggregateReadPort;
import com.parkio.parking.application.port.FraudShadowObserverPort;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudLedgerEntry;
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

class FraudShadowApplicationServiceTest {

    @Test
    void appendsFraudEvaluationForReporterOutcome() {
        RecordingLedger ledger = new RecordingLedger(false);
        UUID reporter = UUID.randomUUID();
        OutcomeHistoryRecord outcome = outcome(
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                reporter);
        FraudShadowApplicationService service = service(ledger, aggregate(reporter, outcome, 1, 1), reporter);

        FraudShadowProcessingResult result = service.process(new ValidatedOutcomeForFraud(outcome, reporter));

        assertThat(result.status()).isEqualTo(FraudShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries).hasSize(1);
        assertThat(ledger.entries.getFirst().disposition()).isNotEqualTo(FraudDisposition.POLICY_UNSUPPORTED);
    }

    @Test
    void duplicateLedgerWriteIsReportedIdempotently() {
        UUID reporter = UUID.randomUUID();
        OutcomeHistoryRecord outcome = outcome(
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                reporter);
        FraudShadowApplicationService service = service(
                new RecordingLedger(true),
                aggregate(reporter, outcome, 1, 1),
                reporter);

        FraudShadowProcessingResult result = service.process(new ValidatedOutcomeForFraud(outcome, reporter));

        assertThat(result.status()).isEqualTo(FraudShadowProcessingResult.Status.DUPLICATE);
    }

    @Test
    void coldStartAggregateProducesInsufficientEvidence() {
        RecordingLedger ledger = new RecordingLedger(false);
        UUID reporter = UUID.randomUUID();
        OutcomeHistoryRecord outcome = outcome(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                90,
                reporter);
        FraudShadowApplicationService service = service(ledger, aggregate(reporter, outcome, 0, 0), reporter);

        FraudShadowProcessingResult result = service.process(new ValidatedOutcomeForFraud(outcome, reporter));

        assertThat(result.status()).isEqualTo(FraudShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries.getFirst().disposition()).isEqualTo(FraudDisposition.INSUFFICIENT_EVIDENCE);
    }

    private static FraudShadowApplicationService service(
            FraudLedgerPort ledger,
            FraudReporterOutcomeAggregate aggregate,
            UUID reporter) {
        return new FraudShadowApplicationService(
                ledger,
                (reporterUserId, windowEnd, windowStart, watermarkOutcomeRecordId) -> aggregate,
                FraudShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));
    }

    private static FraudReporterOutcomeAggregate aggregate(
            UUID reporter,
            OutcomeHistoryRecord outcome,
            int eligible,
            int directIncorrect) {
        Instant start = outcome.evaluatedAt().minus(Duration.ofDays(7));
        return new FraudReporterOutcomeAggregate(
                reporter,
                start,
                outcome.evaluatedAt(),
                outcome.recordId(),
                outcome.evaluatedAt(),
                eligible,
                directIncorrect,
                0,
                Math.max(0, eligible - directIncorrect),
                0,
                0);
    }

    private static OutcomeHistoryRecord outcome(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            UUID reporterIgnored) {
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

    private static final class RecordingLedger implements FraudLedgerPort {

        private final boolean duplicate;
        private final List<FraudLedgerEntry> entries = new ArrayList<>();

        private RecordingLedger(boolean duplicate) {
            this.duplicate = duplicate;
        }

        @Override
        public void append(FraudLedgerEntry entry) {
            if (duplicate) {
                throw new DuplicateFraudLedgerEntryException("duplicate");
            }
            entries.add(entry);
        }

        @Override
        public Optional<FraudLedgerEntry> findByEvaluationId(UUID evaluationId) {
            return entries.stream().filter(entry -> entry.evaluationId().equals(evaluationId)).findFirst();
        }

        @Override
        public List<FraudLedgerEntry> findBySubject(com.parkio.parking.fraud.FraudSubject subject) {
            return entries.stream().filter(entry -> entry.subject().equals(subject)).toList();
        }
    }
}
