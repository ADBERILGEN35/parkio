package com.parkio.parking.application;

import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustEngine;
import com.parkio.parking.trust.TrustEvaluation;
import com.parkio.parking.trust.TrustEvaluationContext;
import com.parkio.parking.trust.TrustEvidence;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustPolicyConfig;
import com.parkio.parking.trust.TrustReplayer;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.ValidatedTrustEvidenceFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class TrustShadowApplicationService {

    private final TrustLedgerPort ledger;
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
        Objects.requireNonNull(snapshots, "snapshots");
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
            TrustEvidence accepted = evidence;
            TrustEvaluationContext context = new TrustEvaluationContext(
                    candidate.outcomeRecord().evaluatedAt(),
                    TrustPolicyConfig.POLICY_VERSION,
                    TrustSnapshotSchemaVersion.V1);
            TrustDomain domain = accepted.domain();
            UUID ledgerEntryId = deterministicId("trust-ledger|" + accepted.evidenceId());
            List<TrustLedgerEntry> existing = ledger.findBySubject(accepted.subject()).stream()
                    .filter(entry -> entry.domain() == domain)
                    .sorted(TrustShadowApplicationService::compareEntries)
                    .toList();
            TrustSnapshot previous = foldBefore(accepted, context, ledgerEntryId, existing);
            TrustEvaluation evaluation = engine.evaluate(previous, accepted, context);
            TrustLedgerEntry entry = new TrustLedgerEntry(
                    ledgerEntryId,
                    deterministicId("trust-evaluation|" + accepted.evidenceId()),
                    accepted.subject(),
                    accepted.domain(),
                    TrustPolicyConfig.POLICY_VERSION,
                    TrustSnapshotSchemaVersion.V1,
                    accepted.attributionMappingVersion(),
                    accepted.sourceOutcomeRecordId(),
                    accepted.evidenceId(),
                    accepted.evidenceGroupId(),
                    accepted.evidenceType(),
                    accepted.contributionRole(),
                    accepted.attributionQuality(),
                    accepted.eligibility(),
                    evaluation.direction(),
                    evaluation.resultingSnapshot().level(),
                    evaluation.evaluatedAt(),
                    clock.instant(),
                    accepted,
                    previous,
                    evaluation);
            ledger.append(entry);
            snapshotWrites.replaceLocked(
                    accepted.subject(),
                    accepted.domain(),
                    () -> foldAll(accepted, context, visibleLedger(accepted, entry)));
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
     * Incremental apply requires evaluatedAt to be in canonical ledger order.
     * Concurrent distinct evidence can persist a later row first; fold already-durable
     * earlier rows as previous, then re-apply later rows so the projection matches replay.
     */
    private List<TrustLedgerEntry> visibleLedger(TrustEvidence evidence, TrustLedgerEntry incoming) {
        List<TrustLedgerEntry> durable = ledger.findBySubject(evidence.subject()).stream()
                .filter(entry -> entry.domain() == evidence.domain())
                .sorted(TrustShadowApplicationService::compareEntries)
                .collect(Collectors.toCollection(ArrayList::new));
        if (durable.stream().noneMatch(entry -> entry.ledgerEntryId().equals(incoming.ledgerEntryId()))) {
            durable.add(incoming);
            durable.sort(TrustShadowApplicationService::compareEntries);
        }
        return List.copyOf(durable);
    }

    private TrustSnapshot foldAll(
            TrustEvidence evidence,
            TrustEvaluationContext incomingContext,
            List<TrustLedgerEntry> ordered) {
        if (ordered.isEmpty()) {
            return engine.initialSnapshot(evidence.subject(), evidence.domain(), incomingContext);
        }
        TrustEvaluationContext initialContext = new TrustEvaluationContext(
                ordered.get(0).evaluatedAt(),
                ordered.get(0).trustPolicyVersion(),
                ordered.get(0).snapshotSchemaVersion());
        TrustSnapshot snapshot = engine.initialSnapshot(evidence.subject(), evidence.domain(), initialContext);
        for (TrustLedgerEntry entry : ordered) {
            snapshot = apply(snapshot, entry);
        }
        return snapshot;
    }

    private TrustSnapshot foldBefore(
            TrustEvidence evidence,
            TrustEvaluationContext incomingContext,
            UUID incomingLedgerId,
            List<TrustLedgerEntry> existing) {
        List<TrustLedgerEntry> before = existing.stream()
                .filter(entry -> compareCanonical(entry, incomingContext.evaluatedAt(), incomingLedgerId) < 0)
                .toList();
        TrustEvaluationContext initialContext = before.isEmpty()
                ? incomingContext
                : new TrustEvaluationContext(
                        before.get(0).evaluatedAt(),
                        before.get(0).trustPolicyVersion(),
                        before.get(0).snapshotSchemaVersion());
        TrustSnapshot snapshot = engine.initialSnapshot(evidence.subject(), evidence.domain(), initialContext);
        for (TrustLedgerEntry entry : before) {
            snapshot = apply(snapshot, entry);
        }
        return snapshot;
    }

    private TrustSnapshot apply(TrustSnapshot snapshot, TrustLedgerEntry entry) {
        return engine.evaluate(
                        snapshot,
                        entry.evidence(),
                        new TrustEvaluationContext(
                                entry.evaluatedAt(),
                                entry.trustPolicyVersion(),
                                entry.snapshotSchemaVersion()))
                .resultingSnapshot();
    }

    private static int compareEntries(TrustLedgerEntry left, TrustLedgerEntry right) {
        return compareCanonical(left, right.evaluatedAt(), right.ledgerEntryId());
    }

    private static int compareCanonical(TrustLedgerEntry entry, Instant evaluatedAt, UUID ledgerEntryId) {
        int byTime = entry.evaluatedAt().compareTo(evaluatedAt);
        if (byTime != 0) {
            return byTime;
        }
        return entry.ledgerEntryId().compareTo(ledgerEntryId);
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

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static void markRollbackOnlyIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // No active Spring transaction in direct unit tests.
        }
    }
}

