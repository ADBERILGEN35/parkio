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
import com.parkio.parking.trust.TrustEngine;
import com.parkio.parking.trust.TrustEvaluationContext;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustPolicyConfig;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.TrustSubject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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

    @Test
    void earlierEvidenceBehindACommittedLaterEvaluationStaysInTheCanonicalSnapshot() {
        RecordingLedger ledger = new RecordingLedger(false);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        UUID reporter = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TrustShadowApplicationService service = new TrustShadowApplicationService(
                ledger,
                snapshots,
                snapshots,
                TrustShadowObserverPort.noop(),
                Clock.fixed(Instant.parse("2026-07-28T11:00:00Z"), ZoneOffset.UTC));

        TrustShadowProcessingResult later = service.process(candidateAt(
                reporter,
                Instant.parse("2026-07-28T10:05:00Z"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")));
        TrustShadowProcessingResult earlier = service.process(candidateAt(
                reporter,
                Instant.parse("2026-07-28T10:00:00Z"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2")));

        assertThat(later.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(earlier.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(ledger.entries).hasSize(2);
        assertThat(snapshots.current).isEqualTo(canonicalSnapshot(ledger.entries));
        assertThat(snapshots.current.lastEvaluatedAt()).isEqualTo(Instant.parse("2026-07-28T10:05:00Z"));
        assertThat(snapshots.current.effectiveEvidenceCount()).isEqualTo(2);
    }

    private static TrustSnapshot canonicalSnapshot(List<TrustLedgerEntry> entries) {
        List<TrustLedgerEntry> ordered = entries.stream()
                .sorted(Comparator.comparing(TrustLedgerEntry::evaluatedAt)
                        .thenComparing(TrustLedgerEntry::ledgerEntryId))
                .toList();
        TrustEngine engine = new TrustEngine();
        TrustSnapshot snapshot = engine.initialSnapshot(
                ordered.get(0).subject(),
                ordered.get(0).domain(),
                new TrustEvaluationContext(
                        ordered.get(0).evaluatedAt(),
                        TrustPolicyConfig.POLICY_VERSION,
                        TrustSnapshotSchemaVersion.V1));
        for (TrustLedgerEntry entry : ordered) {
            snapshot = engine.evaluate(
                    snapshot,
                    entry.evidence(),
                    new TrustEvaluationContext(
                            entry.evaluatedAt(),
                            TrustPolicyConfig.POLICY_VERSION,
                            TrustSnapshotSchemaVersion.V1)).resultingSnapshot();
        }
        return snapshot;
    }

    private static ValidatedOutcomeForTrust candidate(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        return new ValidatedOutcomeForTrust(outcome(classification, reason, confidence), UUID.randomUUID());
    }

    private static ValidatedOutcomeForTrust candidateAt(UUID reporter, Instant evaluatedAt, UUID recordId) {
        return new ValidatedOutcomeForTrust(
                outcomeAt(
                        recordId,
                        evaluatedAt,
                        OutcomeClassification.CONFIRMED_CORRECT,
                        OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                        95),
                reporter);
    }

    private static OutcomeHistoryRecord outcome(
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        return outcomeAt(
                UUID.randomUUID(),
                Instant.parse("2026-07-28T10:00:00Z"),
                classification,
                reason,
                confidence);
    }

    private static OutcomeHistoryRecord outcomeAt(
            UUID recordId,
            Instant evaluatedAt,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence) {
        Instant publishedAt = Instant.parse("2026-07-28T09:00:00Z");
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
                recordId,
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
