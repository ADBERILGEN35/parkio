package com.parkio.parking.application;

import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotRevision;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import com.parkio.parking.trust.CanonicalTrustOrderException;
import com.parkio.parking.trust.TrustEngine;
import com.parkio.parking.trust.TrustEvaluation;
import com.parkio.parking.trust.TrustEvaluationContext;
import com.parkio.parking.trust.TrustEvidence;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustLedgerIds;
import com.parkio.parking.trust.TrustLedgerOrder;
import com.parkio.parking.trust.TrustPolicyConfig;
import com.parkio.parking.trust.TrustReplayer;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.ValidatedTrustEvidenceFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class TrustShadowApplicationService {

    private final TrustLedgerPort ledger;
    private final TrustSnapshotReadPort snapshots;
    private final TrustSnapshotWritePort snapshotWrites;
    private final TrustShadowObserverPort observer;
    private final Clock clock;
    private final TrustEngine engine = new TrustEngine();
    private final TrustReplayer replayer = new TrustReplayer();

    public TrustShadowApplicationService(
            TrustLedgerPort ledger,
            TrustSnapshotReadPort snapshots,
            TrustSnapshotWritePort snapshotWrites,
            TrustShadowObserverPort observer,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotWrites = Objects.requireNonNull(snapshotWrites, "snapshotWrites");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TrustShadowProcessingResult process(ValidatedOutcomeForTrust candidate) {
        Objects.requireNonNull(candidate, "candidate");
        observer.recordOutcomeReceived();
        long started = System.nanoTime();
        TrustEvidence evidence = null;
        try {
            evidence = ValidatedTrustEvidenceFactory.reporterEvidence(candidate.outcomeRecord(), candidate.reporterUserId());
            observer.recordEvidenceProduced(evidence);
            if (evidence.eligibility() != TrustEvidence.Eligibility.ELIGIBLE) {
                observer.recordEvidenceSkipped(evidence);
                return TrustShadowProcessingResult.skipped(candidate.outcomeRecord().recordId());
            }
            TrustEvaluationContext context = new TrustEvaluationContext(
                    candidate.outcomeRecord().evaluatedAt(),
                    TrustPolicyConfig.POLICY_VERSION,
                    TrustSnapshotSchemaVersion.V1);
            TrustEvidence eligibleEvidence = evidence;
            Optional<TrustSnapshotRevision> observed =
                    snapshots.findRevision(eligibleEvidence.subject(), eligibleEvidence.domain());
            TrustSnapshot previous = observed
                    .map(TrustSnapshotRevision::snapshot)
                    .orElseGet(() -> engine.initialSnapshot(
                            eligibleEvidence.subject(),
                            eligibleEvidence.domain(),
                            context));
            Long expectedVersion = observed.map(TrustSnapshotRevision::version).orElse(null);
            TrustEvaluation evaluation;
            try {
                evaluation = engine.evaluate(previous, evidence, context);
            } catch (CanonicalTrustOrderException ex) {
                return appendBehindCommittedHead(candidate, evidence, context, started, expectedVersion);
            }
            if (!incomingIsCanonicalTail(previous, evidence, context)) {
                return appendBehindCommittedHead(candidate, evidence, context, started, expectedVersion);
            }
            TrustLedgerEntry entry = new TrustLedgerEntry(
                    TrustLedgerIds.ledgerEntryId(evidence.evidenceId()),
                    TrustLedgerIds.evaluationId(evidence.evidenceId()),
                    evidence.subject(),
                    evidence.domain(),
                    TrustPolicyConfig.POLICY_VERSION,
                    TrustSnapshotSchemaVersion.V1,
                    evidence.attributionMappingVersion(),
                    evidence.sourceOutcomeRecordId(),
                    evidence.evidenceId(),
                    evidence.evidenceGroupId(),
                    evidence.evidenceType(),
                    evidence.contributionRole(),
                    evidence.attributionQuality(),
                    evidence.eligibility(),
                    evaluation.direction(),
                    evaluation.resultingSnapshot().level(),
                    evaluation.evaluatedAt(),
                    clock.instant(),
                    evidence,
                    previous,
                    evaluation);
            ledger.append(entry);
            snapshotWrites.upsert(evaluation.resultingSnapshot(), expectedVersion);
            Duration duration = Duration.ofNanos(System.nanoTime() - started);
            observer.recordUpdateSuccess(evaluation, duration);
            var replay = replayer.replay(entry);
            if (replay.identical()) {
                observer.recordReplaySuccess(replay);
            } else {
                observer.recordReplayMismatch(replay);
            }
            return TrustShadowProcessingResult.appended(candidate.outcomeRecord().recordId());
        } catch (DuplicateTrustLedgerEntryException ex) {
            if (evidence != null) {
                observer.recordUpdateDuplicate(evidence);
            }
            return TrustShadowProcessingResult.duplicate(candidate.outcomeRecord().recordId());
        } catch (TrustShadowProjectionConflictException ex) {
            markRollbackOnlyIfActive();
            if (evidence != null) {
                observer.recordUpdateFailure(TrustShadowFailureStage.SNAPSHOT_CONFLICT, evidence);
            }
            return TrustShadowProcessingResult.failed(candidate.outcomeRecord().recordId(), TrustShadowFailureStage.SNAPSHOT_CONFLICT);
        } catch (RuntimeException ex) {
            markRollbackOnlyIfActive();
            TrustShadowFailureStage stage = classifyFailure(ex);
            if (evidence != null) {
                observer.recordUpdateFailure(stage, evidence);
            } else {
                observer.recordReplayFailure();
            }
            return TrustShadowProcessingResult.failed(candidate.outcomeRecord().recordId(), stage);
        }
    }

    /**
     * True when appending this evidence onto the observed snapshot preserves unsigned canonical order.
     * The first evaluation has an empty snapshot and stays incremental even though its timestamp equals
     * the initial snapshot time.
     */
    private boolean incomingIsCanonicalTail(
            TrustSnapshot previous,
            TrustEvidence evidence,
            TrustEvaluationContext context) {
        if (previous.effectiveEvidenceCount() == 0 || context.evaluatedAt().isAfter(previous.lastEvaluatedAt())) {
            return true;
        }
        UUID incomingId = TrustLedgerIds.ledgerEntryId(evidence.evidenceId());
        for (TrustLedgerEntry entry : ledger.findBySubject(evidence.subject())) {
            if (entry.domain() != evidence.domain()) {
                continue;
            }
            if (TrustLedgerOrder.compare(context.evaluatedAt(), incomingId, entry.evaluatedAt(), entry.ledgerEntryId()) < 0) {
                return false;
            }
        }
        return true;
    }

    private TrustShadowProcessingResult appendBehindCommittedHead(
            ValidatedOutcomeForTrust candidate,
            TrustEvidence evidence,
            TrustEvaluationContext context,
            long started,
            Long expectedVersion) {
        List<TrustLedgerEntry> existing = ledger.findBySubject(evidence.subject());
        List<CanonicalStep> steps = new ArrayList<>(existing.size() + 1);
        for (TrustLedgerEntry entry : existing) {
            if (entry.domain() != evidence.domain()) {
                continue;
            }
            steps.add(new CanonicalStep(
                    entry.evaluatedAt(),
                    entry.ledgerEntryId(),
                    entry.evidence(),
                    entry.trustPolicyVersion(),
                    entry.snapshotSchemaVersion(),
                    false));
        }
        UUID incomingLedgerId = TrustLedgerIds.ledgerEntryId(evidence.evidenceId());
        steps.add(new CanonicalStep(
                context.evaluatedAt(),
                incomingLedgerId,
                evidence,
                context.trustPolicyVersion(),
                context.snapshotSchemaVersion(),
                true));
        steps.sort(Comparator.comparing(CanonicalStep::evaluatedAt)
                .thenComparing(CanonicalStep::ledgerEntryId, TrustLedgerOrder.UNSIGNED_ID));

        TrustSnapshot cursor = engine.initialSnapshot(
                evidence.subject(),
                evidence.domain(),
                new TrustEvaluationContext(
                        steps.get(0).evaluatedAt(),
                        TrustPolicyConfig.POLICY_VERSION,
                        TrustSnapshotSchemaVersion.V1));
        TrustSnapshot previousForIncoming = null;
        TrustEvaluation incomingEvaluation = null;
        for (CanonicalStep step : steps) {
            TrustEvaluationContext stepContext = new TrustEvaluationContext(
                    step.evaluatedAt(),
                    step.policyVersion(),
                    step.schemaVersion());
            TrustEvaluation folded = engine.evaluate(cursor, step.evidence(), stepContext);
            if (step.incoming()) {
                previousForIncoming = cursor;
                incomingEvaluation = folded;
            }
            cursor = folded.resultingSnapshot();
        }
        Objects.requireNonNull(previousForIncoming, "previousForIncoming");
        Objects.requireNonNull(incomingEvaluation, "incomingEvaluation");
        TrustLedgerEntry entry = new TrustLedgerEntry(
                incomingLedgerId,
                TrustLedgerIds.evaluationId(evidence.evidenceId()),
                evidence.subject(),
                evidence.domain(),
                TrustPolicyConfig.POLICY_VERSION,
                TrustSnapshotSchemaVersion.V1,
                evidence.attributionMappingVersion(),
                evidence.sourceOutcomeRecordId(),
                evidence.evidenceId(),
                evidence.evidenceGroupId(),
                evidence.evidenceType(),
                evidence.contributionRole(),
                evidence.attributionQuality(),
                evidence.eligibility(),
                incomingEvaluation.direction(),
                incomingEvaluation.resultingSnapshot().level(),
                incomingEvaluation.evaluatedAt(),
                clock.instant(),
                evidence,
                previousForIncoming,
                incomingEvaluation);
        ledger.append(entry);
        snapshotWrites.upsert(cursor, expectedVersion);
        Duration duration = Duration.ofNanos(System.nanoTime() - started);
        observer.recordUpdateSuccess(incomingEvaluation, duration);
        var replay = replayer.replay(entry);
        if (replay.identical()) {
            observer.recordReplaySuccess(replay);
        } else {
            observer.recordReplayMismatch(replay);
        }
        return TrustShadowProcessingResult.appended(candidate.outcomeRecord().recordId());
    }

    private record CanonicalStep(
            Instant evaluatedAt,
            UUID ledgerEntryId,
            TrustEvidence evidence,
            String policyVersion,
            TrustSnapshotSchemaVersion schemaVersion,
            boolean incoming) {
    }

    private static TrustShadowFailureStage classifyFailure(RuntimeException ex) {
        if (ex instanceof UnsupportedOperationException) {
            return TrustShadowFailureStage.OBSERVABILITY_FAILURE;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return TrustShadowFailureStage.EVIDENCE_MAPPING_FAILURE;
        }
        return TrustShadowFailureStage.LEDGER_APPEND_FAILURE;
    }

    private static void markRollbackOnlyIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // No active Spring transaction in direct unit tests.
        }
    }
}

