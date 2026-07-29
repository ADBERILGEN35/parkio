package com.parkio.parking.application;

import com.parkio.parking.application.exposure.ExposureShadowFailureStage;
import com.parkio.parking.application.exposure.ExposureShadowProcessingResult;
import com.parkio.parking.application.exposure.SearchExposureEvidenceFactory;
import com.parkio.parking.application.port.ExposureShadowObserverPort;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.exposure.ExposureCandidateId;
import com.parkio.parking.exposure.ExposureComparison;
import com.parkio.parking.exposure.ExposureEngine;
import com.parkio.parking.exposure.ExposureEvaluation;
import com.parkio.parking.exposure.ExposureEvaluationContext;
import com.parkio.parking.exposure.ExposureEvidence;
import com.parkio.parking.exposure.ExposurePolicyConfig;
import com.parkio.parking.exposure.ExposureQueryContext;
import com.parkio.parking.exposure.ExposureReplayComparison;
import com.parkio.parking.exposure.ExposureReplayer;
import com.parkio.parking.exposure.ExposureShadowOrdering;
import com.parkio.parking.exposure.ExposureSnapshot;
import com.parkio.parking.exposure.ExposureSnapshotSchemaVersion;
import com.parkio.parking.exposure.UnsupportedExposurePolicyVersionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ExposureShadowApplicationService {

    private final ExposureShadowObserverPort observer;
    private final Clock clock;
    private final ExposureEngine engine = new ExposureEngine();
    private final ExposureReplayer replayer = new ExposureReplayer();

    public ExposureShadowApplicationService(ExposureShadowObserverPort observer, Clock clock) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExposureShadowProcessingResult evaluateNearbySearch(
            List<ParkingSpot> legacyResults,
            double queryLatitude,
            double queryLongitude,
            double radiusMeters,
            int limit,
            boolean authenticated,
            long timeBudgetMillis) {
        observer.recordRequestReceived();
        long started = System.nanoTime();
        try {
            Instant evaluatedAt = clock.instant();
            ExposureQueryContext queryContext = SearchExposureEvidenceFactory.queryContext(
                    queryLatitude, queryLongitude, radiusMeters, limit, authenticated);
            List<ExposureEvidence> evidenceList = SearchExposureEvidenceFactory.fromSearchResults(
                    legacyResults, queryLatitude, queryLongitude, radiusMeters, evaluatedAt);
            ExposureEvaluationContext evaluationContext = new ExposureEvaluationContext(
                    evaluatedAt,
                    ExposurePolicyConfig.POLICY_VERSION,
                    ExposureSnapshotSchemaVersion.V1);

            List<ExposureEvaluation> evaluations = new ArrayList<>(evidenceList.size());
            for (ExposureEvidence evidence : evidenceList) {
                if (budgetExceeded(started, timeBudgetMillis)) {
                    observer.recordTimeBudgetExceeded();
                    return ExposureShadowProcessingResult.failed(ExposureShadowFailureStage.TIME_BUDGET_EXCEEDED);
                }
                ExposureEvaluation evaluation = engine.evaluate(evidence, evaluationContext);
                evaluations.add(evaluation);
                observer.recordCandidateEvaluated(evaluation);
            }

            List<ExposureCandidateId> legacyOrder = legacyResults.stream()
                    .map(spot -> new ExposureCandidateId(spot.id()))
                    .toList();
            ExposureComparison comparison = ExposureShadowOrdering.compare(
                    queryContext,
                    ExposurePolicyConfig.POLICY_VERSION,
                    ExposureSnapshotSchemaVersion.V1,
                    legacyOrder,
                    evaluations,
                    evaluatedAt);

            Duration duration = Duration.ofNanos(System.nanoTime() - started);
            observer.recordEvaluationSuccess(comparison, duration);

            ExposureSnapshot snapshot = new ExposureSnapshot(
                    ExposurePolicyConfig.POLICY_VERSION,
                    ExposureSnapshotSchemaVersion.V1,
                    queryContext,
                    evaluationContext,
                    evidenceList,
                    evaluations,
                    comparison,
                    evaluatedAt);
            ExposureReplayComparison replay = replayer.replay(snapshot);
            if (replay.identical()) {
                observer.recordReplaySuccess(replay);
            } else {
                observer.recordReplayMismatch(replay);
            }
            return ExposureShadowProcessingResult.success();
        } catch (UnsupportedExposurePolicyVersionException ex) {
            observer.recordEvaluationFailure(ExposureShadowFailureStage.POLICY_VERSION_UNSUPPORTED);
            return ExposureShadowProcessingResult.failed(ExposureShadowFailureStage.POLICY_VERSION_UNSUPPORTED);
        } catch (RuntimeException ex) {
            ExposureShadowFailureStage stage = classifyFailure(ex);
            observer.recordEvaluationFailure(stage);
            return ExposureShadowProcessingResult.failed(stage);
        }
    }

    private static boolean budgetExceeded(long startedNanos, long timeBudgetMillis) {
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        return elapsedMillis > timeBudgetMillis;
    }

    private static ExposureShadowFailureStage classifyFailure(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return ExposureShadowFailureStage.CANDIDATE_MAPPING_FAILURE;
        }
        if (ex instanceof UnsupportedOperationException) {
            return ExposureShadowFailureStage.OBSERVABILITY_FAILURE;
        }
        return ExposureShadowFailureStage.EVALUATION_FAILURE;
    }
}
