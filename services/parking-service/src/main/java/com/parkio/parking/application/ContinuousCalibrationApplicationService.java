package com.parkio.parking.application;

import com.parkio.parking.application.calibration.CalibrationFailureStage;
import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import com.parkio.parking.application.calibration.FraudLedgerCalibrationCandidate;
import com.parkio.parking.application.calibration.TrustOutcomeCalibrationPair;
import com.parkio.parking.application.port.CalibrationObservationPort;
import com.parkio.parking.application.port.CalibrationReadinessPort;
import com.parkio.parking.application.port.CalibrationReportPort;
import com.parkio.parking.application.port.ContinuousCalibrationObserverPort;
import com.parkio.parking.application.port.FraudLedgerCalibrationReadPort;
import com.parkio.parking.application.port.TrustOutcomeCalibrationReadPort;
import com.parkio.parking.calibration.CalibrationAttributionQuality;
import com.parkio.parking.calibration.CalibrationCohortKey;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationLabel;
import com.parkio.parking.calibration.CalibrationLabelCategory;
import com.parkio.parking.calibration.CalibrationLabelFinality;
import com.parkio.parking.calibration.CalibrationLabelQuality;
import com.parkio.parking.calibration.CalibrationLabelSource;
import com.parkio.parking.calibration.CalibrationMappingVersion;
import com.parkio.parking.calibration.CalibrationObservation;
import com.parkio.parking.calibration.CalibrationObservationHorizon;
import com.parkio.parking.calibration.CalibrationPolicyConfig;
import com.parkio.parking.calibration.CalibrationPrediction;
import com.parkio.parking.calibration.CalibrationReadinessAssessor;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.calibration.CalibrationReplayComparison;
import com.parkio.parking.calibration.CalibrationReplayer;
import com.parkio.parking.calibration.CalibrationReport;
import com.parkio.parking.calibration.CalibrationReportGenerator;
import com.parkio.parking.calibration.CalibrationSnapshot;
import com.parkio.parking.calibration.CalibrationSnapshotSchemaVersion;
import com.parkio.parking.calibration.CalibrationWindow;
import com.parkio.parking.fraud.FraudAggregationVersion;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudSnapshotSchemaVersion;
import com.parkio.parking.application.fraud.ReporterFraudFeatureFactory;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.ValidatedTrustEvidenceFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ContinuousCalibrationApplicationService {

    private static final String TRUST_AGGREGATION_VERSION = "trust-aggregation-none-v1";
    private static final int FULL_COMPLETENESS_BASIS_POINTS = CalibrationPolicyConfig.BASIS_POINTS;

    private final CalibrationObservationPort observationPort;
    private final CalibrationReportPort reportPort;
    private final CalibrationReadinessPort readinessPort;
    private final TrustOutcomeCalibrationReadPort trustPairs;
    private final FraudLedgerCalibrationReadPort fraudCandidates;
    private final ContinuousCalibrationObserverPort observer;
    private final Clock clock;
    private final CalibrationPolicyConfig policyConfig = CalibrationPolicyConfig.referenceV1();

    public ContinuousCalibrationApplicationService(
            CalibrationObservationPort observationPort,
            CalibrationReportPort reportPort,
            CalibrationReadinessPort readinessPort,
            TrustOutcomeCalibrationReadPort trustPairs,
            FraudLedgerCalibrationReadPort fraudCandidates,
            ContinuousCalibrationObserverPort observer,
            Clock clock) {
        this.observationPort = Objects.requireNonNull(observationPort, "observationPort");
        this.reportPort = Objects.requireNonNull(reportPort, "reportPort");
        this.readinessPort = Objects.requireNonNull(readinessPort, "readinessPort");
        this.trustPairs = Objects.requireNonNull(trustPairs, "trustPairs");
        this.fraudCandidates = Objects.requireNonNull(fraudCandidates, "fraudCandidates");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CalibrationProcessingResult processTrustBatch(int limit) {
        return processBatch(CalibrationEngineType.TRUST, limit, trustPairs.findUnobservedTrustOutcomePairs(limit));
    }

    public CalibrationProcessingResult processFraudBatch(int limit) {
        return processBatch(
                CalibrationEngineType.FRAUD,
                limit,
                fraudCandidates.findUnobservedFraudEvaluations(limit));
    }

    private CalibrationProcessingResult processBatch(
            CalibrationEngineType engineType, int limit, List<?> candidates) {
        observer.recordSchedulerCandidates(engineType, candidates.size());
        if (candidates.isEmpty()) {
            CalibrationProcessingResult empty = CalibrationProcessingResult.appended(engineType, 0, 0, 0, 0);
            observer.recordProcessingResult(empty);
            return empty;
        }

        int appended = 0;
        int duplicate = 0;
        int failed = 0;
        Instant windowStart = null;
        Instant windowEnd = null;
        Instant watermark = null;
        Set<String> policyVersions = new LinkedHashSet<>();

        for (Object candidate : candidates) {
            observer.recordCandidateReceived(engineType);
            try {
                CalibrationObservation observation = candidate instanceof TrustOutcomeCalibrationPair trustPair
                        ? buildTrustObservation(trustPair)
                        : buildFraudObservation((FraudLedgerCalibrationCandidate) candidate);
                policyVersions.add(observation.prediction().policyVersion());
                windowStart = minInstant(windowStart, observation.predictedAt());
                windowEnd = maxInstant(windowEnd, observation.predictedAt());
                watermark = maxInstant(watermark, observation.labeledAt());
                observationPort.append(observation);
                appended++;
                observer.recordObservationAppended(engineType);
            } catch (DuplicateCalibrationObservationException ex) {
                duplicate++;
                observer.recordObservationDuplicate(engineType);
            } catch (RuntimeException ex) {
                failed++;
                CalibrationFailureStage stage = classifyObservationFailure(ex);
                observer.recordObservationFailure(engineType, stage);
            }
        }

        CalibrationProcessingResult result;
        if (windowStart == null || windowEnd == null) {
            result = summarizeBatch(engineType, candidates.size(), appended, duplicate, failed, Optional.empty());
        } else {
            result = finalizeBatch(
                    engineType,
                    candidates.size(),
                    appended,
                    duplicate,
                    failed,
                    new CalibrationWindow(windowStart, windowEnd),
                    watermark == null ? windowEnd : watermark,
                    List.copyOf(policyVersions));
        }
        observer.recordProcessingResult(result);
        observer.recordSchedulerCompleted(engineType, appended + duplicate);
        return result;
    }

    private CalibrationProcessingResult finalizeBatch(
            CalibrationEngineType engineType,
            int candidateCount,
            int appended,
            int duplicate,
            int failed,
            CalibrationWindow window,
            Instant watermark,
            List<String> policyVersions) {
        Optional<UUID> lastReportId = Optional.empty();
        for (String policyVersion : policyVersions) {
            try {
                lastReportId = Optional.of(generateWindowArtifacts(engineType, policyVersion, window, watermark));
            } catch (DuplicateCalibrationReportException ex) {
                observer.recordReportDuplicate(engineType);
            } catch (RuntimeException ex) {
                CalibrationFailureStage stage = classifyReportFailure(ex);
                observer.recordReportFailure(engineType, stage);
                return CalibrationProcessingResult.failed(engineType, candidateCount, stage);
            }
        }

        if (lastReportId.isPresent()) {
            return CalibrationProcessingResult.reportGenerated(
                    engineType, candidateCount, appended, duplicate, failed, lastReportId.get());
        }
        return summarizeBatch(engineType, candidateCount, appended, duplicate, failed, Optional.empty());
    }

    private UUID generateWindowArtifacts(
            CalibrationEngineType engineType,
            String policyVersion,
            CalibrationWindow window,
            Instant watermark) {
        List<CalibrationObservation> windowObservations =
                observationPort.findByEngineAndWindow(engineType, window.start(), window.end()).stream()
                        .filter(observation -> policyVersion.equals(observation.prediction().policyVersion()))
                        .toList();
        if (windowObservations.isEmpty()) {
            throw new IllegalStateException("no observations in calibration window");
        }

        Set<String> cohortKeys = new LinkedHashSet<>();
        for (CalibrationObservation observation : windowObservations) {
            cohortKeys.add(observation.cohortKey());
        }

        UUID lastReportId = null;
        for (String cohortKeyValue : cohortKeys) {
            CalibrationCohortKey cohortKey = cohortKeyFromCanonical(cohortKeyValue, engineType, policyVersion);
            List<CalibrationObservation> cohortObservations = windowObservations.stream()
                    .filter(observation -> observation.cohortKey().equals(cohortKeyValue))
                    .toList();
            UUID reportId = deterministicReportId(
                    engineType, policyVersion, window.end(), watermark, policyConfig.policyVersion(), cohortKeyValue);
            long started = System.nanoTime();
            CalibrationReport report = CalibrationReportGenerator.generate(
                    reportId,
                    engineType,
                    Optional.of(policyVersion),
                    Optional.empty(),
                    policyConfig,
                    window,
                    cohortKey,
                    cohortObservations,
                    watermark,
                    clock.instant());
            reportPort.append(report);
            observer.recordReportGenerated(report, Duration.ofNanos(System.nanoTime() - started));

            UUID assessmentId = deterministicAssessmentId(reportId);
            CalibrationReadinessAssessment assessment = CalibrationReadinessAssessor.assess(
                    assessmentId,
                    report,
                    policyConfig,
                    shadowOperationalFlags(),
                    clock.instant());
            readinessPort.append(assessment);
            observer.recordReadinessAssessed(assessment);

            CalibrationSnapshot snapshot = new CalibrationSnapshot(
                    CalibrationSnapshotSchemaVersion.V1,
                    CalibrationMappingVersion.V1,
                    policyConfig,
                    report,
                    cohortObservations);
            verifyReplay(engineType, snapshot);
            lastReportId = reportId;
        }
        return Objects.requireNonNull(lastReportId, "lastReportId");
    }

    private void verifyReplay(CalibrationEngineType engineType, CalibrationSnapshot snapshot) {
        try {
            CalibrationReplayComparison comparison =
                    CalibrationReplayer.replayAndCompare(snapshot, clock.instant());
            if (comparison.identical()) {
                observer.recordReplaySuccess(comparison);
            } else {
                observer.recordReplayMismatch(comparison);
            }
        } catch (RuntimeException ex) {
            observer.recordReplayFailure(engineType);
            throw ex;
        }
    }

    private CalibrationObservation buildTrustObservation(TrustOutcomeCalibrationPair pair) {
        CalibrationAttributionQuality attributionQuality = mapAttributionQuality(pair.attributionQuality());
        CalibrationLabel label = trustLabel(pair, attributionQuality);
        CalibrationCohortKey cohortKey = new CalibrationCohortKey(
                CalibrationEngineType.TRUST,
                pair.trustPolicyVersion(),
                pair.trustLevelBand(),
                Optional.of(label.labelCategory()),
                CalibrationObservationHorizon.MEDIUM_TERM);
        CalibrationPrediction prediction = new CalibrationPrediction(
                CalibrationEngineType.TRUST,
                pair.trustPolicyVersion(),
                TrustSnapshotSchemaVersion.V1.value(),
                ValidatedTrustEvidenceFactory.ATTRIBUTION_MAPPING_VERSION,
                TRUST_AGGREGATION_VERSION,
                pair.trustLevelBand(),
                trustPredictedCategory(pair.trustLevelBand()),
                pair.trustEvaluationId());
        UUID observationId = deterministicObservationId(
                CalibrationEngineType.TRUST, pair.trustEvaluationId(), pair.sourceOutcomeId());
        Instant createdAt = clock.instant();
        return new CalibrationObservation(
                observationId,
                CalibrationEngineType.TRUST,
                prediction,
                label,
                CalibrationObservationHorizon.MEDIUM_TERM,
                cohortKey.canonicalKey(),
                attributionQuality,
                FULL_COMPLETENESS_BASIS_POINTS,
                pair.evaluatedAt(),
                pair.labeledAt(),
                createdAt);
    }

    private CalibrationObservation buildFraudObservation(FraudLedgerCalibrationCandidate candidate) {
        CalibrationLabel label = fraudLabel(candidate);
        CalibrationCohortKey cohortKey = new CalibrationCohortKey(
                CalibrationEngineType.FRAUD,
                candidate.fraudPolicyVersion(),
                candidate.riskBand().name(),
                Optional.of(label.labelCategory()),
                CalibrationObservationHorizon.SHORT_TERM);
        CalibrationPrediction prediction = new CalibrationPrediction(
                CalibrationEngineType.FRAUD,
                candidate.fraudPolicyVersion(),
                FraudSnapshotSchemaVersion.V1.value(),
                ReporterFraudFeatureFactory.MAPPING_VERSION,
                FraudAggregationVersion.V1,
                candidate.riskBand().name(),
                fraudPredictedCategory(candidate),
                candidate.evaluationId());
        UUID observationId = deterministicObservationId(
                CalibrationEngineType.FRAUD, candidate.evaluationId(), candidate.sourceOutcomeId());
        Instant createdAt = clock.instant();
        return new CalibrationObservation(
                observationId,
                CalibrationEngineType.FRAUD,
                prediction,
                label,
                CalibrationObservationHorizon.SHORT_TERM,
                cohortKey.canonicalKey(),
                CalibrationAttributionQuality.DIRECT,
                FULL_COMPLETENESS_BASIS_POINTS,
                candidate.evaluatedAt(),
                candidate.evaluatedAt(),
                createdAt);
    }

    private static CalibrationLabel trustLabel(
            TrustOutcomeCalibrationPair pair, CalibrationAttributionQuality attributionQuality) {
        CalibrationLabelCategory category = trustLabelCategory(pair.outcomeClassification(), attributionQuality);
        CalibrationLabelQuality quality = labelQuality(category, attributionQuality);
        CalibrationLabelFinality finality = category == CalibrationLabelCategory.NEUTRAL
                || category == CalibrationLabelCategory.UNKNOWN
                        ? CalibrationLabelFinality.PROVISIONAL
                        : CalibrationLabelFinality.FINAL;
        return new CalibrationLabel(
                category,
                CalibrationLabelSource.OUTCOME_HISTORY,
                quality,
                finality,
                pair.sourceOutcomeId(),
                pair.labeledAt());
    }

    private static CalibrationLabel fraudLabel(FraudLedgerCalibrationCandidate candidate) {
        if (isReviewedFraudDisposition(candidate.disposition())) {
            return new CalibrationLabel(
                    CalibrationLabelCategory.NOT_APPLICABLE,
                    CalibrationLabelSource.OPERATIONAL_METRIC,
                    CalibrationLabelQuality.NONE,
                    CalibrationLabelFinality.FINAL,
                    candidate.sourceOutcomeId(),
                    candidate.evaluatedAt());
        }
        return new CalibrationLabel(
                CalibrationLabelCategory.NEUTRAL,
                CalibrationLabelSource.OUTCOME_HISTORY,
                CalibrationLabelQuality.PARTIAL,
                CalibrationLabelFinality.PROVISIONAL,
                candidate.sourceOutcomeId(),
                candidate.evaluatedAt());
    }

    private static CalibrationLabelCategory trustLabelCategory(
            String outcomeClassification, CalibrationAttributionQuality attributionQuality) {
        return switch (outcomeClassification) {
            case "CONFIRMED_CORRECT", "LIKELY_CORRECT" -> CalibrationLabelCategory.POSITIVE;
            case "CONFIRMED_INCORRECT" -> attributionQuality == CalibrationAttributionQuality.DIRECT
                    ? CalibrationLabelCategory.NEGATIVE
                    : CalibrationLabelCategory.NEUTRAL;
            case "UNKNOWN", "EXPIRED_WITHOUT_EVIDENCE" -> CalibrationLabelCategory.NEUTRAL;
            case "LIKELY_INCORRECT" -> CalibrationLabelCategory.NEUTRAL;
            default -> CalibrationLabelCategory.UNKNOWN;
        };
    }

    private static CalibrationLabelQuality labelQuality(
            CalibrationLabelCategory category, CalibrationAttributionQuality attributionQuality) {
        if (category == CalibrationLabelCategory.NOT_APPLICABLE) {
            return CalibrationLabelQuality.NONE;
        }
        return switch (attributionQuality) {
            case DIRECT -> CalibrationLabelQuality.DIRECT;
            case STRONG -> CalibrationLabelQuality.STRONG;
            case PARTIAL -> CalibrationLabelQuality.PARTIAL;
            case AMBIGUOUS -> CalibrationLabelQuality.AMBIGUOUS;
            case NONE -> CalibrationLabelQuality.NONE;
        };
    }

    private static CalibrationAttributionQuality mapAttributionQuality(String attributionQuality) {
        try {
            return CalibrationAttributionQuality.valueOf(attributionQuality);
        } catch (IllegalArgumentException ex) {
            return CalibrationAttributionQuality.AMBIGUOUS;
        }
    }

    private static boolean isReviewedFraudDisposition(FraudDisposition disposition) {
        return disposition == FraudDisposition.REVIEW_CANDIDATE
                || disposition == FraudDisposition.ELEVATED_RISK;
    }

    private static String trustPredictedCategory(String trustLevelBand) {
        return switch (trustLevelBand) {
            case "HIGH_CONFIDENCE", "ESTABLISHED" -> "POSITIVE";
            case "LOW_CONFIDENCE" -> "NEGATIVE";
            default -> "NEUTRAL";
        };
    }

    private static String fraudPredictedCategory(FraudLedgerCalibrationCandidate candidate) {
        return switch (candidate.riskBand()) {
            case CRITICAL, HIGH -> "CRITICAL";
            case ELEVATED -> "ELEVATED";
            case LOW -> "LOW";
            case MINIMAL -> "NEUTRAL";
        };
    }

    private static CalibrationCohortKey cohortKeyFromCanonical(
            String cohortKeyValue, CalibrationEngineType engineType, String policyVersion) {
        String[] parts = cohortKeyValue.split("\\|", -1);
        if (parts.length == 5) {
            CalibrationObservationHorizon horizon = CalibrationObservationHorizon.valueOf(parts[4]);
            Optional<CalibrationLabelCategory> labelCategory = "*".equals(parts[3])
                    ? Optional.empty()
                    : Optional.of(CalibrationLabelCategory.valueOf(parts[3]));
            return new CalibrationCohortKey(
                    CalibrationEngineType.valueOf(parts[0]),
                    parts[1],
                    parts[2],
                    labelCategory,
                    horizon);
        }
        return new CalibrationCohortKey(
                engineType,
                policyVersion,
                "UNKNOWN",
                Optional.empty(),
                CalibrationObservationHorizon.AT_EVALUATION);
    }

    private static CalibrationReadinessAssessor.OperationalFlags shadowOperationalFlags() {
        return new CalibrationReadinessAssessor.OperationalFlags(
                false, false, false, false, false);
    }

    private static CalibrationProcessingResult summarizeBatch(
            CalibrationEngineType engineType,
            int candidateCount,
            int appended,
            int duplicate,
            int failed,
            Optional<UUID> reportId) {
        if (failed == candidateCount && candidateCount > 0) {
            return CalibrationProcessingResult.failed(
                    engineType, candidateCount, CalibrationFailureStage.OBSERVATION_APPEND);
        }
        if (duplicate == candidateCount && appended == 0) {
            return CalibrationProcessingResult.duplicate(engineType, candidateCount, duplicate);
        }
        if (reportId.isPresent()) {
            return CalibrationProcessingResult.reportGenerated(
                    engineType, candidateCount, appended, duplicate, failed, reportId.get());
        }
        return CalibrationProcessingResult.appended(engineType, candidateCount, appended, duplicate, failed);
    }

    private static CalibrationFailureStage classifyObservationFailure(RuntimeException ex) {
        if (ex instanceof UnsupportedOperationException) {
            return CalibrationFailureStage.OBSERVABILITY;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return CalibrationFailureStage.OBSERVATION_BUILD;
        }
        return CalibrationFailureStage.OBSERVATION_APPEND;
    }

    private static CalibrationFailureStage classifyReportFailure(RuntimeException ex) {
        if (ex instanceof DuplicateCalibrationReportException) {
            return CalibrationFailureStage.REPORT_GENERATION;
        }
        if (ex instanceof UnsupportedOperationException) {
            return CalibrationFailureStage.OBSERVABILITY;
        }
        if (ex.getMessage() != null && ex.getMessage().contains("replay")) {
            return CalibrationFailureStage.REPLAY;
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return CalibrationFailureStage.REPORT_GENERATION;
        }
        return CalibrationFailureStage.READINESS_ASSESSMENT;
    }

    private static UUID deterministicObservationId(
            CalibrationEngineType engineType, UUID evaluationId, UUID sourceOutcomeId) {
        String material = "calibration-observation-v1|"
                + engineType.name()
                + '|'
                + evaluationId
                + '|'
                + sourceOutcomeId;
        return deterministicId(material);
    }

    private static UUID deterministicReportId(
            CalibrationEngineType engineType,
            String policyVersion,
            Instant windowEnd,
            Instant watermark,
            String calibrationPolicyVersion,
            String cohortKey) {
        String material = "calibration-report-v1|"
                + engineType.name()
                + '|'
                + policyVersion
                + '|'
                + windowEnd
                + '|'
                + watermark
                + '|'
                + calibrationPolicyVersion
                + '|'
                + cohortKey;
        return deterministicId(material);
    }

    private static UUID deterministicAssessmentId(UUID reportId) {
        return deterministicId("calibration-readiness-v1|" + reportId);
    }

    private static UUID deterministicId(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant minInstant(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        return current.isBefore(candidate) ? current : candidate;
    }

    private static Instant maxInstant(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        return current.isAfter(candidate) ? current : candidate;
    }
}
