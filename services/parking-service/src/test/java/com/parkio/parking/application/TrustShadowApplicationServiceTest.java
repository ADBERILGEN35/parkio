package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
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
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSubject;
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

class TrustShadowApplicationServiceTest {

    @Test
    void appendsShadowTrustUpdateForEligibleReporterOutcome() {
        RecordingLedger ledger = new RecordingLedger(false);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        TrustShadowApplicationService service = new TrustShadowApplicationService(
                ledger,
                snapshots,
                snapshots,
                TrustShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        TrustShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95));

        assertThat(result.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries).hasSize(1);
        assertThat(snapshots.current).isNotNull();
        assertThat(snapshots.current.domain()).isEqualTo(TrustDomain.PARKING_REPORT_ACCURACY);
    }

    @Test
    void duplicateLedgerWriteIsReportedIdempotently() {
        TrustShadowApplicationService service = new TrustShadowApplicationService(
                new RecordingLedger(true),
                new RecordingSnapshots(),
                new RecordingSnapshots(),
                TrustShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        TrustShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95));

        assertThat(result.status()).isEqualTo(TrustShadowProcessingResult.Status.DUPLICATE);
    }

    @Test
    void ambiguousOutcomeIsSkippedWithoutLedgerAppend() {
        RecordingLedger ledger = new RecordingLedger(false);
        TrustShadowApplicationService service = new TrustShadowApplicationService(
                ledger,
                new RecordingSnapshots(),
                new RecordingSnapshots(),
                TrustShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        TrustShadowProcessingResult result = service.process(candidate(
                OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE,
                OutcomeReason.TIME_EXPIRED_NO_EVIDENCE,
                20));

        assertThat(result.status()).isEqualTo(TrustShadowProcessingResult.Status.SKIPPED);
        assertThat(ledger.entries).isEmpty();
    }

    private static ValidatedOutcomeForTrust candidate(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        return new ValidatedOutcomeForTrust(outcome(classification, reason, confidence), UUID.randomUUID());
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

    private static final class RecordingLedger implements TrustLedgerPort {
        private final boolean duplicate;
        private final List<TrustLedgerEntry> entries = new ArrayList<>();

        private RecordingLedger(boolean duplicate) {
            this.duplicate = duplicate;
        }

        @Override
        public void append(TrustLedgerEntry entry) {
            if (duplicate) {
                throw new DuplicateTrustLedgerEntryException("duplicate");
            }
            entries.add(entry);
        }

        @Override
        public Optional<TrustLedgerEntry> findByEvaluationId(UUID evaluationId) {
            return entries.stream().filter(entry -> entry.evaluationId().equals(evaluationId)).findFirst();
        }

        @Override
        public List<TrustLedgerEntry> findBySubject(TrustSubject subject) {
            return entries.stream().filter(entry -> entry.subject().equals(subject)).toList();
        }
    }

    private static final class RecordingSnapshots implements TrustSnapshotReadPort, TrustSnapshotWritePort {
        private TrustSnapshot current;

        @Override
        public Optional<TrustSnapshot> findBySubjectAndDomain(TrustSubject subject, TrustDomain domain) {
            return Optional.ofNullable(current).filter(snapshot ->
                    snapshot.subject().equals(subject) && snapshot.domain() == domain);
        }

        @Override
        public void upsert(TrustSnapshot snapshot) {
            this.current = snapshot;
        }
    }
}
