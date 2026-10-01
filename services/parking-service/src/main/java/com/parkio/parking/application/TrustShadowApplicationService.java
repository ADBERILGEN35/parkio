package com.parkio.parking.application;

import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
            TrustSnapshot previous = snapshots.findBySubjectAndDomain(eligibleEvidence.subject(), eligibleEvidence.domain())
                    .orElseGet(() -> engine.initialSnapshot(
                            eligibleEvidence.subject(),
                            eligibleEvidence.domain(),
                            context));
            TrustEvaluation evaluation;
            try {
                evaluation = engine.evaluate(previous, evidence, context);
            } catch (CanonicalTrustOrderException ex) {
                return appendBehindCommittedHead(candidate, evidence, context, started);
            }
            TrustLedgerEntry entry = new TrustLedgerEntry(
                    deterministicId("trust-ledger|" + evidence.evidenceId()),
                    deterministicId("trust-evaluation|" + evidence.evidenceId()),
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
            snapshotWrites.upsert(evaluation.resultingSnapshot());
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
     * A later evaluation already committed. Append this older evidence and replace the snapshot
     * with a fold of the ledger in evaluatedAt order so the earlier update is not dropped.
     */
    private TrustShadowProcessingResult appendBehindCommittedHead(
            ValidatedOutcomeForTrust candidate,
            TrustEvidence evidence,
            TrustEvaluationContext context,
            long started) {
        List<TrustLedgerEntry> existing = ledger.findBySubject(evidence.subject());
        List<CanonicalStep> steps = new ArrayList<>(existing.size() + 1);
        for (TrustLedgerEntry entry : existing) {
            steps.add(new CanonicalStep(entry.evaluatedAt(), entry.ledgerEntryId(), entry.evidence(), false));
        }
        UUID incomingLedgerId = deterministicId("trust-ledger|" + evidence.evidenceId());
        steps.add(new CanonicalStep(context.evaluatedAt(), incomingLedgerId, evidence, true));
        steps.sort(Comparator.comparing(CanonicalStep::evaluatedAt).thenComparing(CanonicalStep::ledgerEntryId));

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
            TrustEvaluationContext stepContext = step.incoming()
                    ? context
                    : new TrustEvaluationContext(
                            step.evaluatedAt(),
                            TrustPolicyConfig.POLICY_VERSION,
                            TrustSnapshotSchemaVersion.V1);
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
                deterministicId("trust-evaluation|" + evidence.evidenceId()),
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
        snapshotWrites.upsert(cursor);
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

