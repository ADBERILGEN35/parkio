package com.parkio.parking.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalibrationReportGeneratorTest {

    private static final Instant WINDOW_START = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-28T11:00:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-07-28T11:30:00Z");
    private static final Instant SOURCE_WATERMARK = Instant.parse("2026-07-28T10:30:00Z");
    private static final String POLICY_VERSION = "trust-policy-v1";
    private static final CalibrationCohortKey COHORT = new CalibrationCohortKey(
            CalibrationEngineType.TRUST,
            POLICY_VERSION,
            "ESTABLISHED",
            Optional.empty(),
            CalibrationObservationHorizon.MEDIUM_TERM);

    @Test
    void unknownLabelsExcludedFromPrecisionDenominator() {
        CalibrationPolicyConfig policy = testPolicy(3, 1);
        List<CalibrationObservation> observations = List.of(
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.POSITIVE, "POSITIVE", "ESTABLISHED"),
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.UNKNOWN, "POSITIVE", "ESTABLISHED"),
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.UNKNOWN, "POSITIVE", "ESTABLISHED"));

        CalibrationReport report = generate(policy, CalibrationEngineType.TRUST, observations);

        CalibrationMetricValue precision = report.metric(CalibrationMetricType.PRECISION).orElseThrow();
        assertThat(precision.applicability()).isEqualTo(CalibrationMetricApplicability.APPLICABLE);
        assertThat(precision.numerator()).isEqualTo(1L);
        assertThat(precision.denominator()).isEqualTo(1L);
    }

    @Test
    void insufficientDataWhenBelowMinimumObservations() {
        CalibrationPolicyConfig policy = CalibrationPolicyConfig.referenceV1();
        List<CalibrationObservation> observations = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            observations.add(observation(
                    CalibrationEngineType.TRUST,
                    CalibrationLabelCategory.POSITIVE,
                    "POSITIVE",
                    "ESTABLISHED"));
        }

        CalibrationReport report = generate(policy, CalibrationEngineType.TRUST, observations);

        assertThat(report.reportStatus()).isEqualTo(CalibrationReportStatus.INSUFFICIENT_DATA);
        assertThat(report.metric(CalibrationMetricType.PRECISION).orElseThrow().applicability())
                .isEqualTo(CalibrationMetricApplicability.INSUFFICIENT_DATA);
    }

    @Test
    void notApplicablePrecisionForFraudWithAllNotApplicableLabels() {
        CalibrationPolicyConfig policy = testPolicy(3, 1);
        List<CalibrationObservation> observations = List.of(
                observation(
                        CalibrationEngineType.FRAUD,
                        CalibrationLabelCategory.NOT_APPLICABLE,
                        "CRITICAL",
                        "HIGH"),
                observation(
                        CalibrationEngineType.FRAUD,
                        CalibrationLabelCategory.NOT_APPLICABLE,
                        "ELEVATED",
                        "ELEVATED"),
                observation(
                        CalibrationEngineType.FRAUD,
                        CalibrationLabelCategory.NOT_APPLICABLE,
                        "LOW",
                        "LOW"));

        CalibrationReport report = generate(policy, CalibrationEngineType.FRAUD, observations);

        CalibrationMetricValue precision = report.metric(CalibrationMetricType.PRECISION).orElseThrow();
        assertThat(precision.applicability()).isEqualTo(CalibrationMetricApplicability.NOT_APPLICABLE);
    }

    @Test
    void sameInputsSameReport() {
        CalibrationPolicyConfig policy = testPolicy(3, 1);
        List<CalibrationObservation> observations = List.of(
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.POSITIVE, "POSITIVE", "ESTABLISHED"),
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.NEGATIVE, "NEGATIVE", "LOW_CONFIDENCE"),
                observation(CalibrationEngineType.TRUST, CalibrationLabelCategory.POSITIVE, "POSITIVE", "ESTABLISHED"));
        UUID reportId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        CalibrationReport first = CalibrationReportGenerator.generate(
                reportId,
                CalibrationEngineType.TRUST,
                Optional.of(POLICY_VERSION),
                Optional.empty(),
                policy,
                new CalibrationWindow(WINDOW_START, WINDOW_END),
                COHORT,
                observations,
                SOURCE_WATERMARK,
                GENERATED_AT);
        CalibrationReport second = CalibrationReportGenerator.generate(
                reportId,
                CalibrationEngineType.TRUST,
                Optional.of(POLICY_VERSION),
                Optional.empty(),
                policy,
                new CalibrationWindow(WINDOW_START, WINDOW_END),
                COHORT,
                observations,
                SOURCE_WATERMARK,
                GENERATED_AT);

        assertThat(second).isEqualTo(first);
    }

    private static CalibrationReport generate(
            CalibrationPolicyConfig policy,
            CalibrationEngineType engineType,
            List<CalibrationObservation> observations) {
        CalibrationCohortKey cohort = engineType == CalibrationEngineType.FRAUD
                ? new CalibrationCohortKey(
                        CalibrationEngineType.FRAUD,
                        "fraud-policy-v1",
                        "HIGH",
                        Optional.empty(),
                        CalibrationObservationHorizon.SHORT_TERM)
                : COHORT;
        return CalibrationReportGenerator.generate(
                UUID.randomUUID(),
                engineType,
                Optional.of(engineType == CalibrationEngineType.FRAUD ? "fraud-policy-v1" : POLICY_VERSION),
                Optional.empty(),
                policy,
                new CalibrationWindow(WINDOW_START, WINDOW_END),
                cohort,
                observations,
                SOURCE_WATERMARK,
                GENERATED_AT);
    }

    private static CalibrationPolicyConfig testPolicy(int minimumObservations, int minimumLabeledObservations) {
        return new CalibrationPolicyConfig(
                CalibrationPolicyConfig.POLICY_VERSION,
                minimumObservations,
                minimumLabeledObservations,
                5_000,
                9_900,
                100,
                7_000,
                7_000,
                2);
    }

    private static CalibrationObservation observation(
            CalibrationEngineType engineType,
            CalibrationLabelCategory labelCategory,
            String predictedCategory,
            String predictedBand) {
        Instant predictedAt = Instant.parse("2026-07-28T10:00:00Z");
        UUID evaluationId = UUID.randomUUID();
        String policyVersion = engineType == CalibrationEngineType.FRAUD ? "fraud-policy-v1" : POLICY_VERSION;
        String cohortBand = engineType == CalibrationEngineType.FRAUD ? "HIGH" : predictedBand;
        CalibrationLabel label = new CalibrationLabel(
                labelCategory,
                labelCategory == CalibrationLabelCategory.NOT_APPLICABLE
                        ? CalibrationLabelSource.OPERATIONAL_METRIC
                        : CalibrationLabelSource.OUTCOME_HISTORY,
                labelCategory == CalibrationLabelCategory.NOT_APPLICABLE
                        ? CalibrationLabelQuality.NONE
                        : CalibrationLabelQuality.DIRECT,
                CalibrationLabelFinality.FINAL,
                UUID.randomUUID(),
                predictedAt);
        CalibrationObservationHorizon horizon = engineType == CalibrationEngineType.FRAUD
                ? CalibrationObservationHorizon.SHORT_TERM
                : CalibrationObservationHorizon.MEDIUM_TERM;
        CalibrationCohortKey cohortKey = new CalibrationCohortKey(
                engineType,
                policyVersion,
                cohortBand,
                Optional.empty(),
                horizon);
        CalibrationPrediction prediction = new CalibrationPrediction(
                engineType,
                policyVersion,
                "schema-v1",
                "mapping-v1",
                "aggregation-v1",
                predictedBand,
                predictedCategory,
                evaluationId);
        return new CalibrationObservation(
                UUID.randomUUID(),
                engineType,
                prediction,
                label,
                horizon,
                cohortKey.canonicalKey(),
                CalibrationAttributionQuality.DIRECT,
                CalibrationPolicyConfig.BASIS_POINTS,
                predictedAt,
                predictedAt,
                predictedAt);
    }
}
