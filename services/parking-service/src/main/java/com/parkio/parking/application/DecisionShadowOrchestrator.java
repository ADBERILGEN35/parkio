package com.parkio.parking.application;

import com.parkio.parking.application.port.DecisionAuditWriteObserver;
import com.parkio.parking.application.port.DecisionShadowObserverPort;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.audit.DecisionAuditRecordFactory;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservationFactory;
import com.parkio.parking.decision.calibration.ShadowFailureStage;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.decision.port.EvidenceCollectionPort;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowDecisionComparison;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-boundary shadow Decision Engine orchestration.
 *
 * <p>Runs only when enabled. Never mutates publication, never throws into the
 * authoritative AI apply path. Does not load ParkingSpot (no extra DB read for
 * evidence); successful evaluations may append an immutable audit snapshot.
 */
public final class DecisionShadowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DecisionShadowOrchestrator.class);

    private final boolean enabled;
    private final DecisionEngine engine;
    private final EvidenceCollectionPort evidenceCollection;
    private final DecisionShadowObserverPort observer;
    private final DecisionAuditPort auditPort;
    private final DecisionAuditWriteObserver auditWriteObserver;

    public DecisionShadowOrchestrator(
            boolean enabled,
            DecisionEngine engine,
            EvidenceCollectionPort evidenceCollection,
            DecisionShadowObserverPort observer) {
        this(
                enabled,
                engine,
                evidenceCollection,
                observer,
                DecisionAuditPort.noop(),
                DecisionAuditWriteObserver.noop());
    }

    public DecisionShadowOrchestrator(
            boolean enabled,
            DecisionEngine engine,
            EvidenceCollectionPort evidenceCollection,
            DecisionShadowObserverPort observer,
            DecisionAuditPort auditPort,
            DecisionAuditWriteObserver auditWriteObserver) {
        this.enabled = enabled;
        this.engine = Objects.requireNonNull(engine, "engine");
        this.evidenceCollection = Objects.requireNonNull(evidenceCollection, "evidenceCollection");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.auditWriteObserver = Objects.requireNonNull(auditWriteObserver, "auditWriteObserver");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Best-effort shadow evaluation after authoritative apply.
     * Failures are isolated and never rethrown.
     */
    public void observeAfterApply(
            AiValidationEvidenceInput input,
            AiValidationApplyOutcome applyOutcome,
            Instant evaluatedAt) {
        if (!enabled) {
            return;
        }

        long startedNanos = System.nanoTime();
        UUID spotId = null;
        UUID evaluationId = null;
        try {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(applyOutcome, "applyOutcome");
            spotId = input.parkingSpotId();
            evaluationId = input.eventId();

            observer.recordAttempt();

            EvidenceVector vector;
            try {
                EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                        input.parkingSpotId(),
                        input.eventId(),
                        input.occurredAt(),
                        input,
                        null);
                vector = evidenceCollection.collect(request);
            } catch (RuntimeException ex) {
                safeRecordFailure(ShadowFailureStage.EVIDENCE_COLLECTION, elapsed(startedNanos));
                debugFailure(spotId, evaluationId, ex);
                return;
            }

            Instant observedAt = evaluatedAt != null ? evaluatedAt : input.occurredAt();
            EvaluationContext context = EvaluationContext.of(
                    ShadowDecisionPolicyConfig.POLICY_VERSION, observedAt, "runtime-shadow");

            DecisionResult decision;
            try {
                decision = engine.evaluate(vector, context);
            } catch (IllegalArgumentException ex) {
                safeRecordFailure(classifyIllegalArgument(ex), elapsed(startedNanos));
                debugFailure(spotId, evaluationId, ex);
                return;
            } catch (RuntimeException ex) {
                safeRecordFailure(ShadowFailureStage.UNKNOWN, elapsed(startedNanos));
                debugFailure(spotId, evaluationId, ex);
                return;
            }

            Duration duration = elapsed(startedNanos);
            LegacyPublicationOutcome legacy = toLegacy(applyOutcome);
            ShadowDecisionComparison comparison = ShadowDecisionComparison.of(legacy, decision);
            DecisionCalibrationObservation observation =
                    DecisionCalibrationObservationFactory.from(
                            vector, decision, comparison, duration, observedAt);

            try {
                observer.recordSuccess(observation);
            } catch (RuntimeException ex) {
                safeRecordFailure(ShadowFailureStage.OBSERVABILITY, duration);
            }

            safeAppendAudit(vector, context, decision, comparison, observation, observedAt);

            if (log.isDebugEnabled()) {
                log.debug(
                        "Decision shadow ok spotId={} evaluationId={} disposition={} comparison={}",
                        vector.parkingSpotId(),
                        vector.evaluationId(),
                        decision.disposition(),
                        comparison.category());
            }
        } catch (RuntimeException ex) {
            safeRecordFailure(ShadowFailureStage.UNKNOWN, elapsed(startedNanos));
            debugFailure(spotId, evaluationId, ex);
        }
    }

    private void safeAppendAudit(
            EvidenceVector vector,
            EvaluationContext context,
            DecisionResult decision,
            ShadowDecisionComparison comparison,
            DecisionCalibrationObservation observation,
            Instant observedAt) {
        try {
            DecisionAuditRecord record = DecisionAuditRecordFactory.fromSuccessfulShadow(
                    UUID.randomUUID(),
                    vector,
                    context,
                    decision,
                    comparison,
                    observation,
                    observedAt);
            auditPort.append(record);
            try {
                auditWriteObserver.onWriteSuccess();
            } catch (RuntimeException ignored) {
                // Metrics must never affect audit or publication.
            }
        } catch (RuntimeException ex) {
            try {
                auditWriteObserver.onWriteFailure();
            } catch (RuntimeException ignored) {
                // never rethrow
            }
            if (log.isDebugEnabled()) {
                log.debug(
                        "Decision audit append failed spotId={} evaluationId={} reason={}",
                        vector.parkingSpotId(),
                        vector.evaluationId(),
                        ex.getMessage());
            }
        }
    }

    private void safeRecordFailure(ShadowFailureStage stage, Duration duration) {
        try {
            observer.recordFailure(stage, duration);
        } catch (RuntimeException ignored) {
            // Never let observability failures escape the shadow path.
        }
    }

    private static ShadowFailureStage classifyIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage();
        if (message != null && message.toLowerCase().contains("unsupported evaluation policy")) {
            return ShadowFailureStage.CONFIGURATION;
        }
        return ShadowFailureStage.DECISION_POLICY;
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static void debugFailure(UUID spotId, UUID evaluationId, Exception ex) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Decision shadow failed spotId={} evaluationId={} reason={}",
                    spotId,
                    evaluationId,
                    ex.getMessage());
        }
    }

    private static LegacyPublicationOutcome toLegacy(AiValidationApplyOutcome applyOutcome) {
        LegacyPublicationOutcome.Kind kind = switch (applyOutcome.kind()) {
            case STALE -> LegacyPublicationOutcome.Kind.STALE;
            case UNKNOWN_STATUS -> LegacyPublicationOutcome.Kind.UNKNOWN_STATUS;
            case NO_CHANGE -> LegacyPublicationOutcome.Kind.NO_CHANGE;
            case APPLIED -> LegacyPublicationOutcome.Kind.APPLIED;
        };
        return new LegacyPublicationOutcome(
                applyOutcome.previousStatus(), applyOutcome.resultingStatus(), kind);
    }
}