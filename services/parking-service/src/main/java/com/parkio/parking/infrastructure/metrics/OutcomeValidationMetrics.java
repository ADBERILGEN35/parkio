package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.outcome.OutcomeConfidenceBand;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.application.outcome.OutcomeProcessingFailureStage;
import com.parkio.parking.application.outcome.OutcomeProcessingResult;
import com.parkio.parking.application.port.OutcomeOperationalizationObserverPort;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class OutcomeValidationMetrics implements OutcomeOperationalizationObserverPort {

    private static final String POLICY_VERSION = OutcomePolicyConfig.POLICY_VERSION.value();
    private static final String SNAPSHOT_SCHEMA_VERSION = "outcome-snapshot-v1";

    private final Counter triggerReceived;
    private final Counter historyAppendSuccess;
    private final Counter historyAppendDuplicate;
    private final Counter historyAppendFailure;
    private final Counter schedulerFailed;
    private final Counter replaySuccess;
    private final Counter replayMismatch;
    private final Counter replayFailure;
    private final Timer duration;
    private final Map<OutcomeClassification, Counter> classificationCounters;
    private final Map<OutcomeEvaluationTrigger, Counter> triggerOutcomeAppended;
    private final Map<OutcomeEvaluationTrigger, Counter> triggerOutcomeSkipped;
    private final Map<OutcomeEvaluationTrigger, Counter> triggerOutcomeDuplicate;
    private final Map<OutcomeEvaluationTrigger, Counter> triggerReceivedByType;
    private final Map<OutcomeProcessingFailureStage, Counter> failureCounters;
    private final Map<OutcomeConfidenceBand, Counter> confidenceBandCounters;
    private final Counter validationWindowOpenCounter;
    private final Counter validationWindowClosedCounter;
    private final Counter expiredWithoutEvidenceCounter;
    private final Counter schedulerCandidates;
    private final Counter schedulerCompleted;

    public OutcomeValidationMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        this.triggerReceived = Counter.builder("parkio.parking.outcome.trigger.received")
                .description("Outcome trigger rows received")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.historyAppendSuccess = Counter.builder("parkio.parking.outcome.history.append.success")
                .description("Outcome history appends that created a new durable row")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.historyAppendDuplicate = Counter.builder("parkio.parking.outcome.history.append.duplicate")
                .description("Outcome history append attempts resolved as deterministic duplicates")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.historyAppendFailure = Counter.builder("parkio.parking.outcome.history.append.failure")
                .description("Outcome history append failures")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.schedulerFailed = Counter.builder("parkio.parking.outcome.scheduler.failed")
                .description("Outcome trigger scheduler ticks that failed")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.replaySuccess = Counter.builder("parkio.parking.outcome.replay.success")
                .description("Successful replay parity checks")
                .tag("policy_version", POLICY_VERSION)
                .tag("snapshot_schema_version", SNAPSHOT_SCHEMA_VERSION)
                .register(registry);
        this.replayMismatch = Counter.builder("parkio.parking.outcome.replay.mismatch")
                .description("Replay mismatches against stored outcome snapshots")
                .tag("policy_version", POLICY_VERSION)
                .tag("snapshot_schema_version", SNAPSHOT_SCHEMA_VERSION)
                .register(registry);
        this.replayFailure = Counter.builder("parkio.parking.outcome.replay.failure")
                .description("Replay failures")
                .tag("policy_version", POLICY_VERSION)
                .tag("snapshot_schema_version", SNAPSHOT_SCHEMA_VERSION)
                .register(registry);
        this.duration = Timer.builder("parkio.parking.outcome.processing.duration")
                .description("Outcome trigger processing duration")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.validationWindowOpenCounter = Counter.builder("parkio.parking.outcome.validation_window")
                .description("Outcome evaluations while the validation window was open")
                .tag("policy_version", POLICY_VERSION)
                .tag("open", "true")
                .register(registry);
        this.validationWindowClosedCounter = Counter.builder("parkio.parking.outcome.validation_window")
                .description("Outcome evaluations after the validation window had closed")
                .tag("policy_version", POLICY_VERSION)
                .tag("open", "false")
                .register(registry);
        this.expiredWithoutEvidenceCounter = Counter.builder("parkio.parking.outcome.expired_without_evidence")
                .description("Outcome evaluations classified expired without evidence")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.schedulerCandidates = Counter.builder("parkio.parking.outcome.scheduler.candidates")
                .description("Outcome trigger rows claimed by the scheduler")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);
        this.schedulerCompleted = Counter.builder("parkio.parking.outcome.scheduler.completed")
                .description("Outcome trigger rows settled by the scheduler")
                .tag("policy_version", POLICY_VERSION)
                .register(registry);

        this.classificationCounters = countersByClassification(registry);
        this.triggerOutcomeAppended = countersByTrigger(registry, "parkio.parking.outcome.trigger.eligible", "eligible");
        this.triggerOutcomeSkipped = countersByTrigger(registry, "parkio.parking.outcome.trigger.skipped", "skipped");
        this.triggerOutcomeDuplicate = countersByTrigger(registry, "parkio.parking.outcome.trigger.duplicate", "duplicate");
        this.triggerReceivedByType = countersByTrigger(registry, "parkio.parking.outcome.trigger.received.by_type", "received");
        this.failureCounters = countersByFailure(registry);
        this.confidenceBandCounters = countersByConfidenceBand(registry);
    }

    @Override
    public void recordTriggerReceived(OutcomeEvaluationTrigger triggerType) {
        triggerReceived.increment();
        triggerReceivedByType.get(triggerType).increment();
    }

    @Override
    public void recordTriggerOutcome(OutcomeEvaluationTrigger triggerType, OutcomeProcessingResult result) {
        switch (result.status()) {
            case APPENDED -> triggerOutcomeAppended.get(triggerType).increment();
            case DUPLICATE -> triggerOutcomeDuplicate.get(triggerType).increment();
            case INELIGIBLE -> triggerOutcomeSkipped.get(triggerType).increment();
            case FAILED -> result.failureStage().ifPresent(stage -> failureCounters.get(stage).increment());
        }
    }

    @Override
    public void recordEvaluation(OutcomeEvaluation evaluation, Duration evalDuration) {
        recordEvaluationSuccess(evaluation, null, OutcomeConfidenceBand.from(evaluation.confidence()), evalDuration);
    }

    @Override
    public void recordEvaluationSuccess(
            OutcomeEvaluation evaluation,
            OutcomeEvaluationTrigger triggerType,
            OutcomeConfidenceBand confidenceBand,
            Duration evalDuration) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(confidenceBand, "confidenceBand");
        if (evalDuration != null && !evalDuration.isNegative()) {
            duration.record(evalDuration);
        }
        classificationCounters.get(evaluation.classification()).increment();
        confidenceBandCounters.get(confidenceBand).increment();
        if (evaluation.validationWindowOpen()) {
            validationWindowOpenCounter.increment();
        } else {
            validationWindowClosedCounter.increment();
        }
        if (evaluation.primaryReason() == OutcomeReason.TIME_EXPIRED_NO_EVIDENCE
                || evaluation.classification() == OutcomeClassification.EXPIRED_WITHOUT_EVIDENCE) {
            expiredWithoutEvidenceCounter.increment();
        }
    }

    @Override
    public void recordEvaluationFailure(OutcomeProcessingFailureStage stage, OutcomeEvaluationTrigger triggerType) {
        failureCounters.get(stage).increment();
    }

    @Override
    public void recordHistoryAppendSuccess() {
        historyAppendSuccess.increment();
    }

    @Override
    public void recordHistoryAppendDuplicate() {
        historyAppendDuplicate.increment();
    }

    @Override
    public void recordHistoryAppendFailure() {
        historyAppendFailure.increment();
    }

    @Override
    public void recordSchedulerCandidates(int count) {
        if (count > 0) {
            schedulerCandidates.increment(count);
        }
    }

    @Override
    public void recordSchedulerCompleted(int count) {
        if (count > 0) {
            schedulerCompleted.increment(count);
        }
    }

    @Override
    public void recordSchedulerFailed() {
        schedulerFailed.increment();
    }

    @Override
    public void recordReplaySuccess() {
        replaySuccess.increment();
    }

    @Override
    public void recordReplayMismatch() {
        replayMismatch.increment();
    }

    @Override
    public void recordReplayFailure() {
        replayFailure.increment();
    }

    private Map<OutcomeClassification, Counter> countersByClassification(MeterRegistry registry) {
        EnumMap<OutcomeClassification, Counter> counters = new EnumMap<>(OutcomeClassification.class);
        for (OutcomeClassification classification : OutcomeClassification.values()) {
            counters.put(classification, Counter.builder("parkio.parking.outcome.classification")
                    .description("Outcome evaluations by classification")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("classification", classification.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    private Map<OutcomeEvaluationTrigger, Counter> countersByTrigger(
            MeterRegistry registry,
            String metricName,
            String descriptionSuffix) {
        EnumMap<OutcomeEvaluationTrigger, Counter> counters = new EnumMap<>(OutcomeEvaluationTrigger.class);
        for (OutcomeEvaluationTrigger trigger : OutcomeEvaluationTrigger.values()) {
            counters.put(trigger, Counter.builder(metricName)
                    .description("Outcome trigger " + descriptionSuffix + " counts by trigger type")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("trigger_type", trigger.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    private Map<OutcomeProcessingFailureStage, Counter> countersByFailure(MeterRegistry registry) {
        EnumMap<OutcomeProcessingFailureStage, Counter> counters = new EnumMap<>(OutcomeProcessingFailureStage.class);
        for (OutcomeProcessingFailureStage stage : OutcomeProcessingFailureStage.values()) {
            counters.put(stage, Counter.builder("parkio.parking.outcome.evaluation.failure")
                    .description("Outcome trigger processing failures by bounded stage")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("failure_stage", stage.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    private Map<OutcomeConfidenceBand, Counter> countersByConfidenceBand(MeterRegistry registry) {
        EnumMap<OutcomeConfidenceBand, Counter> counters = new EnumMap<>(OutcomeConfidenceBand.class);
        for (OutcomeConfidenceBand band : OutcomeConfidenceBand.values()) {
            counters.put(band, Counter.builder("parkio.parking.outcome.confidence_band")
                    .description("Outcome evaluations by bounded confidence band")
                    .tag("policy_version", POLICY_VERSION)
                    .tag("confidence_band", band.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }
}