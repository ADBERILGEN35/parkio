package com.parkio.parking.application;

import com.parkio.parking.application.outcome.OutcomeConfidenceBand;
import com.parkio.parking.application.outcome.OutcomeEvaluationTriggerRequest;
import com.parkio.parking.application.outcome.OutcomeProcessingFailureStage;
import com.parkio.parking.application.outcome.OutcomeProcessingResult;
import com.parkio.parking.application.port.OutcomeOperationalizationObserverPort;
import com.parkio.parking.application.port.OutcomeSpotSnapshotReadPort;
import com.parkio.parking.application.port.OutcomeStatusHistoryReadPort;
import com.parkio.parking.application.port.OutcomeVerificationReadPort;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.engine.OutcomeValidationEngine;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.history.OutcomeSnapshotSchemaVersion;
import com.parkio.parking.outcome.normalization.OutcomeHistoricalEvidenceFactory;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import com.parkio.parking.outcome.port.OutcomeHistoryPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OutcomeValidationApplicationService {

    private final OutcomeSpotSnapshotReadPort spotSnapshots;
    private final OutcomeStatusHistoryReadPort statusHistory;
    private final OutcomeVerificationReadPort verifications;
    private final OutcomeHistoryPort history;
    private final OutcomeOperationalizationObserverPort observer;
    private final OutcomeValidationEngine engine;
    private final Clock clock;
    private final Duration activeDuration;

    public OutcomeValidationApplicationService(
            OutcomeSpotSnapshotReadPort spotSnapshots,
            OutcomeStatusHistoryReadPort statusHistory,
            OutcomeVerificationReadPort verifications,
            OutcomeHistoryPort history,
            OutcomeOperationalizationObserverPort observer,
            ModerationPolicy moderationPolicy,
            Clock clock) {
        this.spotSnapshots = Objects.requireNonNull(spotSnapshots, "spotSnapshots");
        this.statusHistory = Objects.requireNonNull(statusHistory, "statusHistory");
        this.verifications = Objects.requireNonNull(verifications, "verifications");
        this.history = Objects.requireNonNull(history, "history");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeDuration = Objects.requireNonNull(moderationPolicy, "moderationPolicy").activeDuration();
        this.engine = new OutcomeValidationEngine();
    }

    public OutcomeProcessingResult process(OutcomeEvaluationTriggerRequest trigger) {
        Objects.requireNonNull(trigger, "trigger");
        observer.recordTriggerReceived(trigger.triggerType());
        long startedNanos = System.nanoTime();
        try {
            var spot = spotSnapshots.findSpotSnapshot(trigger.parkingSpotId()).orElse(null);
            if (spot == null) {
                OutcomeProcessingResult result = OutcomeProcessingResult.ineligible(trigger.evaluationId(), trigger.triggerType());
                observer.recordTriggerOutcome(trigger.triggerType(), result);
                return result;
            }
            var evidence = OutcomeHistoricalEvidenceFactory.create(
                    spot,
                    activeDuration,
                    statusHistory.findStatusHistoryForOutcome(trigger.parkingSpotId(), trigger.evidenceCutoffAt()),
                    verifications.findVerificationsForOutcome(trigger.parkingSpotId(), trigger.evidenceCutoffAt()));
            if (evidence.activatedAt() == null) {
                OutcomeProcessingResult result = OutcomeProcessingResult.ineligible(trigger.evaluationId(), trigger.triggerType());
                observer.recordTriggerOutcome(trigger.triggerType(), result);
                return result;
            }
            Instant evaluatedAt = trigger.evidenceCutoffAt();
            OutcomeEvaluationContext context = new OutcomeEvaluationContext(
                    evaluatedAt,
                    OutcomePolicyConfig.POLICY_VERSION,
                    activeDuration);
            OutcomeEvaluation evaluation = engine.evaluate(evidence, context);
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            observer.recordEvaluation(evaluation, duration);
            observer.recordEvaluationSuccess(
                    evaluation,
                    trigger.triggerType(),
                    OutcomeConfidenceBand.from(evaluation.confidence()),
                    duration);
            OutcomeSnapshot snapshot = new OutcomeSnapshot(evidence, context, evaluation);
            OutcomeHistoryRecord record = new OutcomeHistoryRecord(
                    UUID.nameUUIDFromBytes(("outcome-history|" + trigger.evaluationId()).getBytes(StandardCharsets.UTF_8)),
                    trigger.evaluationId(),
                    trigger.parkingSpotId(),
                    evaluation.policyVersion(),
                    OutcomeSnapshotSchemaVersion.V1,
                    trigger.triggerType(),
                    trigger.triggerReference(),
                    evaluation.evaluatedAt(),
                    trigger.evidenceCutoffAt(),
                    snapshot,
                    evaluation.classification(),
                    evaluation.confidence(),
                    evaluation.primaryReason(),
                    evaluation.validationWindowOpen(),
                    clock.instant());
            history.append(record);
            observer.recordHistoryAppendSuccess();
            OutcomeProcessingResult result = OutcomeProcessingResult.appended(trigger.evaluationId(), trigger.triggerType());
            observer.recordTriggerOutcome(trigger.triggerType(), result);
            return result;
        } catch (DuplicateOutcomeHistoryException ex) {
            observer.recordHistoryAppendDuplicate();
            OutcomeProcessingResult result = OutcomeProcessingResult.duplicate(trigger.evaluationId(), trigger.triggerType());
            observer.recordTriggerOutcome(trigger.triggerType(), result);
            return result;
        } catch (RuntimeException ex) {
            OutcomeProcessingFailureStage stage = classifyFailure(ex);
            observer.recordEvaluationFailure(stage, trigger.triggerType());
            if (stage == OutcomeProcessingFailureStage.HISTORY_APPEND_FAILURE) {
                observer.recordHistoryAppendFailure();
            }
            OutcomeProcessingResult result = OutcomeProcessingResult.failed(trigger.evaluationId(), trigger.triggerType(), stage);
            observer.recordTriggerOutcome(trigger.triggerType(), result);
            return result;
        }
    }

    private static OutcomeProcessingFailureStage classifyFailure(RuntimeException ex) {
        if (ex instanceof DuplicateOutcomeHistoryException) {
            return OutcomeProcessingFailureStage.DUPLICATE_ALREADY_RECORDED;
        }
        if (ex.getClass().getSimpleName().contains("UnsupportedOutcomePolicyVersion")) {
            return OutcomeProcessingFailureStage.POLICY_VERSION_UNSUPPORTED;
        }
        if (ex instanceof IllegalStateException || ex instanceof IllegalArgumentException) {
            return OutcomeProcessingFailureStage.EVALUATION_FAILURE;
        }
        return OutcomeProcessingFailureStage.HISTORY_APPEND_FAILURE;
    }
}