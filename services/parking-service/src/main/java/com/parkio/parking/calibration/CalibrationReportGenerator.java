package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CalibrationReportGenerator {

    private CalibrationReportGenerator() {}

    public static CalibrationReport generate(
            UUID reportId,
            CalibrationEngineType engineType,
            Optional<String> baselinePolicyVersion,
            Optional<String> candidatePolicyVersion,
            CalibrationPolicyConfig policyConfig,
            CalibrationWindow window,
            CalibrationCohortKey cohortKey,
            List<CalibrationObservation> observations,
            Instant sourceWatermark,
            Instant generatedAt) {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(baselinePolicyVersion, "baselinePolicyVersion");
        Objects.requireNonNull(candidatePolicyVersion, "candidatePolicyVersion");
        Objects.requireNonNull(policyConfig, "policyConfig");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cohortKey, "cohortKey");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(sourceWatermark, "sourceWatermark");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (!CalibrationPolicyConfig.POLICY_VERSION.equals(policyConfig.policyVersion())) {
            throw new UnsupportedCalibrationPolicyVersionException(policyConfig.policyVersion());
        }

        List<CalibrationObservation> cohortObservations = observations.stream()
                .filter(observation -> observation.engineType() == engineType)
                .filter(observation -> observation.cohortKey().equals(cohortKey.canonicalKey()))
                .toList();

        long observationCount = cohortObservations.size();
        long labeledCount = cohortObservations.stream().filter(CalibrationObservation::isLabeled).count();

        CalibrationReportStatus reportStatus = observationCount < policyConfig.minimumObservations()
                ? CalibrationReportStatus.INSUFFICIENT_DATA
                : CalibrationReportStatus.GENERATED;

        List<CalibrationMetricValue> metrics = new ArrayList<>();
        metrics.add(CalibrationMetricValue.ratio(
                CalibrationMetricType.OBSERVATION_COUNT, observationCount, 1L, Optional.empty()));
        metrics.add(CalibrationMetricValue.ratio(
                CalibrationMetricType.LABELED_COUNT, labeledCount, 1L, Optional.empty()));
        metrics.add(labelCoverageMetric(observationCount, labeledCount, policyConfig));
        metrics.add(classificationMetric(
                CalibrationMetricType.PRECISION,
                cohortObservations,
                policyConfig,
                ClassificationSlice.TRUE_POSITIVE,
                reportStatus));
        metrics.add(classificationMetric(
                CalibrationMetricType.RECALL,
                cohortObservations,
                policyConfig,
                ClassificationSlice.TRUE_POSITIVE,
                reportStatus));
        metrics.add(classificationMetric(
                CalibrationMetricType.SPECIFICITY,
                cohortObservations,
                policyConfig,
                ClassificationSlice.TRUE_NEGATIVE,
                reportStatus));
        metrics.add(classificationMetric(
                CalibrationMetricType.FALSE_POSITIVE_RATE,
                cohortObservations,
                policyConfig,
                ClassificationSlice.FALSE_POSITIVE,
                reportStatus));
        metrics.addAll(bandCalibrationMetrics(engineType, cohortObservations, policyConfig));
        metrics.add(replayMatchMetric(reportStatus, policyConfig));
        metrics.add(driftStatusMetric(baselinePolicyVersion, candidatePolicyVersion, reportStatus));

        return new CalibrationReport(
                reportId,
                engineType,
                baselinePolicyVersion,
                candidatePolicyVersion,
                policyConfig.policyVersion(),
                window,
                cohortKey,
                observationCount,
                labeledCount,
                metrics,
                reportStatus,
                sourceWatermark,
                generatedAt);
    }

    public static CalibrationReport regenerate(CalibrationSnapshot snapshot, Instant generatedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(generatedAt, "generatedAt");
        CalibrationReport original = snapshot.report();
        return generate(
                original.reportId(),
                original.engineType(),
                original.baselinePolicyVersion(),
                original.candidatePolicyVersion(),
                snapshot.policyConfig(),
                original.window(),
                original.cohortKey(),
                snapshot.observations(),
                original.sourceWatermark(),
                generatedAt);
    }

    private static CalibrationMetricValue labelCoverageMetric(
            long observationCount, long labeledCount, CalibrationPolicyConfig policyConfig) {
        if (observationCount < policyConfig.minimumObservations()) {
            return CalibrationMetricValue.insufficientData(
                    CalibrationMetricType.LABEL_COVERAGE, labeledCount, observationCount);
        }
        return CalibrationMetricValue.ratio(
                CalibrationMetricType.LABEL_COVERAGE, labeledCount, observationCount, Optional.empty());
    }

    private static CalibrationMetricValue classificationMetric(
            CalibrationMetricType type,
            List<CalibrationObservation> observations,
            CalibrationPolicyConfig policyConfig,
            ClassificationSlice slice,
            CalibrationReportStatus reportStatus) {
        if (reportStatus == CalibrationReportStatus.INSUFFICIENT_DATA) {
            return CalibrationMetricValue.insufficientData(type, 0L, observations.size());
        }
        if (!supportsClassificationMetrics(observations)) {
            return CalibrationMetricValue.notApplicable(type, "classification metrics require labeled observations");
        }

        long truePositive = 0L;
        long falsePositive = 0L;
        long trueNegative = 0L;
        long falseNegative = 0L;

        for (CalibrationObservation observation : observations) {
            if (!observation.label().countsTowardClassificationMetrics()) {
                continue;
            }
            boolean predictedPositive = isPredictedPositive(observation.prediction());
            boolean actualPositive = observation.label().labelCategory() == CalibrationLabelCategory.POSITIVE;
            if (actualPositive && predictedPositive) {
                truePositive++;
            } else if (actualPositive) {
                falseNegative++;
            } else if (predictedPositive) {
                falsePositive++;
            } else {
                trueNegative++;
            }
        }

        long numerator;
        long denominator;
        switch (slice) {
            case TRUE_POSITIVE -> {
                if (type == CalibrationMetricType.PRECISION) {
                    numerator = truePositive;
                    denominator = truePositive + falsePositive;
                } else {
                    numerator = truePositive;
                    denominator = truePositive + falseNegative;
                }
            }
            case TRUE_NEGATIVE -> {
                numerator = trueNegative;
                denominator = trueNegative + falsePositive;
            }
            case FALSE_POSITIVE -> {
                numerator = falsePositive;
                denominator = falsePositive + trueNegative;
            }
            default -> throw new IllegalStateException("Unexpected slice: " + slice);
        }

        if (denominator < policyConfig.minimumLabeledObservations()) {
            return CalibrationMetricValue.insufficientData(type, numerator, denominator);
        }
        return CalibrationMetricValue.ratio(type, numerator, denominator, Optional.empty());
    }

    private static boolean supportsClassificationMetrics(List<CalibrationObservation> observations) {
        return observations.stream().anyMatch(observation -> observation.label().countsTowardClassificationMetrics());
    }

    private static List<CalibrationMetricValue> bandCalibrationMetrics(
            CalibrationEngineType engineType,
            List<CalibrationObservation> observations,
            CalibrationPolicyConfig policyConfig) {
        if (engineType != CalibrationEngineType.TRUST && engineType != CalibrationEngineType.FRAUD) {
            return List.of(CalibrationMetricValue.notApplicable(
                    CalibrationMetricType.OBSERVED_POSITIVE_RATE_BY_BAND,
                    "band calibration applies to trust and fraud engines only"));
        }

        Map<String, BandAccumulator> byBand = new HashMap<>();
        for (CalibrationObservation observation : observations) {
            if (!observation.label().countsTowardClassificationMetrics()) {
                continue;
            }
            String band = observation.prediction().predictedBand();
            BandAccumulator accumulator = byBand.computeIfAbsent(band, ignored -> new BandAccumulator());
            accumulator.total++;
            if (observation.label().labelCategory() == CalibrationLabelCategory.POSITIVE) {
                accumulator.positive++;
            }
        }

        List<CalibrationMetricValue> metrics = new ArrayList<>();
        for (Map.Entry<String, BandAccumulator> entry : byBand.entrySet()) {
            BandAccumulator accumulator = entry.getValue();
            if (accumulator.total < policyConfig.bandCalibrationMinimumObservations()) {
                metrics.add(CalibrationMetricValue.insufficientData(
                        CalibrationMetricType.OBSERVED_POSITIVE_RATE_BY_BAND,
                        accumulator.positive,
                        accumulator.total));
            } else {
                metrics.add(CalibrationMetricValue.ratio(
                        CalibrationMetricType.OBSERVED_POSITIVE_RATE_BY_BAND,
                        accumulator.positive,
                        accumulator.total,
                        Optional.of("band=" + entry.getKey())));
            }
        }

        if (metrics.isEmpty()) {
            metrics.add(CalibrationMetricValue.notApplicable(
                    CalibrationMetricType.OBSERVED_POSITIVE_RATE_BY_BAND,
                    "no band-labeled observations"));
        }
        return metrics;
    }

    private static CalibrationMetricValue replayMatchMetric(
            CalibrationReportStatus reportStatus, CalibrationPolicyConfig policyConfig) {
        if (reportStatus != CalibrationReportStatus.GENERATED) {
            return CalibrationMetricValue.insufficientData(CalibrationMetricType.REPLAY_MATCH_RATE, 0L, 0L);
        }
        return CalibrationMetricValue.ratio(
                CalibrationMetricType.REPLAY_MATCH_RATE,
                policyConfig.minimumReplayMatchRateBasisPoints(),
                CalibrationPolicyConfig.BASIS_POINTS,
                Optional.of("placeholder until replay comparison is bound"));
    }

    private static CalibrationMetricValue driftStatusMetric(
            Optional<String> baselinePolicyVersion,
            Optional<String> candidatePolicyVersion,
            CalibrationReportStatus reportStatus) {
        if (reportStatus != CalibrationReportStatus.GENERATED) {
            return CalibrationMetricValue.insufficientData(CalibrationMetricType.DRIFT_STATUS, 0L, 0L);
        }
        if (baselinePolicyVersion.isEmpty() || candidatePolicyVersion.isEmpty()) {
            return CalibrationMetricValue.notApplicable(
                    CalibrationMetricType.DRIFT_STATUS, "baseline and candidate policy versions required");
        }
        if (baselinePolicyVersion.get().equals(candidatePolicyVersion.get())) {
            return CalibrationMetricValue.ratio(
                    CalibrationMetricType.DRIFT_STATUS,
                    0L,
                    1L,
                    Optional.of("stable"));
        }
        return CalibrationMetricValue.ratio(
                CalibrationMetricType.DRIFT_STATUS,
                1L,
                1L,
                Optional.of("candidate_differs"));
    }

    private static boolean isPredictedPositive(CalibrationPrediction prediction) {
        String category = prediction.predictedCategory();
        return "POSITIVE".equalsIgnoreCase(category)
                || "HIGH".equalsIgnoreCase(category)
                || "ELEVATED".equalsIgnoreCase(category)
                || "CRITICAL".equalsIgnoreCase(category);
    }

    private enum ClassificationSlice {
        TRUE_POSITIVE,
        TRUE_NEGATIVE,
        FALSE_POSITIVE
    }

    private static final class BandAccumulator {
        private long total;
        private long positive;
    }
}