package com.parkio.parking.application;

import com.parkio.parking.application.fraud.FraudReporterOutcomeAggregate;
import com.parkio.parking.application.fraud.FraudShadowFailureStage;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.fraud.ReporterFraudFeatureFactory;
import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import com.parkio.parking.application.port.FraudLedgerPort;
import com.parkio.parking.application.port.FraudReporterOutcomeAggregateReadPort;
import com.parkio.parking.application.port.FraudShadowObserverPort;
import com.parkio.parking.fraud.FraudAggregationVersion;
import com.parkio.parking.fraud.FraudDomain;
import com.parkio.parking.fraud.FraudEngine;
import com.parkio.parking.fraud.FraudEvaluation;
import com.parkio.parking.fraud.FraudEvaluationContext;
import com.parkio.parking.fraud.FraudFeatureVector;
import com.parkio.parking.fraud.FraudLedgerEntry;
import com.parkio.parking.fraud.FraudPolicyConfig;
import com.parkio.parking.fraud.FraudReplayer;
import com.parkio.parking.fraud.FraudSnapshot;
import com.parkio.parking.fraud.FraudSnapshotSchemaVersion;
import com.parkio.parking.fraud.FraudSubject;
import com.parkio.parking.fraud.FraudSubjectType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FraudShadowApplicationService {

    private final FraudLedgerPort ledger;
    private final FraudReporterOutcomeAggregateReadPort aggregates;
    private final FraudShadowObserverPort observer;
    private final Clock clock;
    private final FraudEngine engine = new FraudEngine();
    private final FraudReplayer replayer = new FraudReplayer();

    public FraudShadowApplicationService(
            FraudLedgerPort ledger,
            FraudReporterOutcomeAggregateReadPort aggregates,
            FraudShadowObserverPort observer,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FraudShadowProcessingResult process(ValidatedOutcomeForFraud candidate) {
        Objects.requireNonNull(candidate, "candidate");
        observer.recordCandidateReceived();
        long started = System.nanoTime();
        FraudFeatureVector features = null;
        try {
            Instant evaluatedAt = candidate.outcomeRecord().evaluatedAt();
            Instant windowStart = ReporterFraudFeatureFactory.windowStartFor(candidate.outcomeRecord(), evaluatedAt);
            FraudReporterOutcomeAggregate aggregate = aggregates.aggregateReporterContributions(
                    candidate.reporterUserId(),
                    evaluatedAt,
                    windowStart,
                    candidate.outcomeRecord().recordId());
            features = ReporterFraudFeatureFactory.fromAggregate(aggregate);
            observer.recordFeatureVectorProduced(features);

            FraudEvaluationContext context = new FraudEvaluationContext(
                    evaluatedAt,
                    FraudPolicyConfig.POLICY_VERSION,
                    FraudSnapshotSchemaVersion.V1,
                    ReporterFraudFeatureFactory.MAPPING_VERSION);
            FraudEvaluation evaluation = engine.evaluate(features, context);
            FraudSnapshot snapshot = new FraudSnapshot(
                    features.subject(),
                    features.domain(),
                    FraudPolicyConfig.POLICY_VERSION,
                    FraudSnapshotSchemaVersion.V1,
                    ReporterFraudFeatureFactory.MAPPING_VERSION,
                    FraudAggregationVersion.V1,
                    context,
                    features,
                    evaluation,
                    evaluatedAt);
            UUID evaluationId = deterministicEvaluationId(features);
            FraudLedgerEntry entry = new FraudLedgerEntry(
                    deterministicId("fraud-ledger|" + evaluationId),
                    evaluationId,
                    features.subject(),
                    features.domain(),
                    FraudPolicyConfig.POLICY_VERSION,
                    FraudSnapshotSchemaVersion.V1,
                    ReporterFraudFeatureFactory.MAPPING_VERSION,
                    FraudAggregationVersion.V1,
                    candidate.outcomeRecord().recordId(),
                    features.windowStart(),
                    features.windowEnd(),
                    evaluation.riskScore().basisPoints(),
                    evaluation.riskBand(),
                    evaluation.confidenceBand(),
                    evaluation.evidenceVolume().count(),
                    evaluation.disposition(),
                    evaluation.decisiveRule(),
                    evaluation.evaluatedAt(),
                    clock.instant(),
                    snapshot);
            ledger.append(entry);
            Duration duration = Duration.ofNanos(System.nanoTime() - started);
            observer.recordEvaluationSuccess(evaluation, duration);
            var replay = replayer.replay(entry);
            if (replay.identical()) {
                observer.recordReplaySuccess(replay);
            } else {
                observer.recordReplayMismatch(replay);
            }
            return FraudShadowProcessingResult.appended(candidate.outcomeRecord().recordId());
        } catch (DuplicateFraudLedgerEntryException ex) {
            if (features != null) {
                observer.recordEvaluationDuplicate(features);
            }
            return FraudShadowProcessingResult.duplicate(candidate.outcomeRecord().recordId());
        } catch (RuntimeException ex) {
            FraudShadowFailureStage stage = classifyFailure(ex);
            if (features != null) {
                observer.recordEvaluationFailure(stage, features);
            } else {
                observer.recordReplayFailure();
            }
            return FraudShadowProcessingResult.failed(candidate.outcomeRecord().recordId(), stage);
        }
    }

    private static UUID deterministicEvaluationId(FraudFeatureVector features) {
        String material = "fraud-evaluation-v1|"
                + features.subject().type().name() + '|'
                + features.subject().subjectId() + '|'
                + features.domain().name() + '|'
                + features.sourceWatermarkOutcomeRecordId() + '|'
                + FraudPolicyConfig.POLICY_VERSION + '|'
                + features.aggregationVersion();
        return deterministicId(material);
    }

    private static FraudShadowFailureStage classifyFailure(RuntimeException ex) {
        if (ex instanceof UnsupportedOperationException) {
            return FraudShadowFailureStage.OBSERVABILITY_FAILURE;
        }
        if (ex.getClass().getSimpleName().contains("UnsupportedFraudPolicyVersion")) {
            return FraudShadowFailureStage.POLICY_VERSION_UNSUPPORTED;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return FraudShadowFailureStage.AGGREGATION_FAILURE;
        }
        return FraudShadowFailureStage.LEDGER_APPEND_FAILURE;
    }

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
