package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public final class CalibrationReadinessAssessor {

    private CalibrationReadinessAssessor() {}

    public record OperationalFlags(
            boolean operationallyVerified,
            boolean privacyReviewComplete,
            boolean fairnessReviewComplete,
            boolean regressionDetected,
            boolean calibrationComplete) {}

    public static CalibrationReadinessAssessment assess(
            UUID assessmentId,
            CalibrationReport report,
            CalibrationPolicyConfig policyConfig,
            OperationalFlags operationalFlags,
            Instant assessedAt) {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(policyConfig, "policyConfig");
        Objects.requireNonNull(operationalFlags, "operationalFlags");
        Objects.requireNonNull(assessedAt, "assessedAt");
        if (!CalibrationPolicyConfig.POLICY_VERSION.equals(policyConfig.policyVersion())) {
            throw new UnsupportedCalibrationPolicyVersionException(policyConfig.policyVersion());
        }

        List<CalibrationReadinessReason> reasons = new ArrayList<>();
        if (report.reportStatus() == CalibrationReportStatus.FAILED) {
            reasons.add(CalibrationReadinessReason.REPORT_NOT_GENERATED);
            return buildAssessment(
                    assessmentId, report, CalibrationReadinessStatus.NOT_READY, reasons, assessedAt);
        }
        if (report.reportStatus() == CalibrationReportStatus.INSUFFICIENT_DATA) {
            reasons.add(CalibrationReadinessReason.REPORT_INSUFFICIENT_DATA);
            reasons.add(CalibrationReadinessReason.MIN_OBSERVATIONS);
            return buildAssessment(
                    assessmentId, report, CalibrationReadinessStatus.INSUFFICIENT_DATA, reasons, assessedAt);
        }
        if (report.reportStatus() != CalibrationReportStatus.GENERATED) {
            reasons.add(CalibrationReadinessReason.REPORT_NOT_GENERATED);
            return buildAssessment(
                    assessmentId, report, CalibrationReadinessStatus.NOT_READY, reasons, assessedAt);
        }

        if (report.observationCount() < policyConfig.minimumObservations()) {
            reasons.add(CalibrationReadinessReason.MIN_OBSERVATIONS);
        }
        if (report.labeledCount() < policyConfig.minimumLabeledObservations()) {
            reasons.add(CalibrationReadinessReason.MIN_LABELED);
        }

        OptionalInt labelCoverage = metricBasisPoints(report, CalibrationMetricType.LABEL_COVERAGE);
        if (labelCoverage.isEmpty()
                || labelCoverage.getAsInt() < policyConfig.minimumLabelCoverageBasisPoints()) {
            reasons.add(CalibrationReadinessReason.LABEL_COVERAGE_LOW);
        }

        OptionalInt replayMatch = metricBasisPoints(report, CalibrationMetricType.REPLAY_MATCH_RATE);
        if (replayMatch.isPresent()) {
            int mismatch = CalibrationPolicyConfig.BASIS_POINTS - replayMatch.getAsInt();
            if (mismatch > policyConfig.maximumReplayMismatchBasisPoints()) {
                reasons.add(CalibrationReadinessReason.REPLAY_MISMATCH);
            }
        }

        if (!operationalFlags.operationallyVerified()) {
            reasons.add(CalibrationReadinessReason.OPERATIONAL_VERIFICATION_MISSING);
        }
        if (!operationalFlags.calibrationComplete()) {
            reasons.add(CalibrationReadinessReason.CALIBRATION_INCOMPLETE);
        }
        if (operationalFlags.regressionDetected()) {
            reasons.add(CalibrationReadinessReason.REGRESSION_DETECTED);
        }
        if (!operationalFlags.fairnessReviewComplete()) {
            reasons.add(CalibrationReadinessReason.FAIRNESS_REVIEW_REQUIRED);
        }
        if (!operationalFlags.privacyReviewComplete()) {
            reasons.add(CalibrationReadinessReason.PRIVACY_REVIEW_REQUIRED);
        }

        if (hasClassificationInsufficientData(report)) {
            reasons.add(CalibrationReadinessReason.METRIC_INSUFFICIENT_DATA);
        }

        Set<CalibrationReadinessReason> blocking = EnumSet.copyOf(reasons);
        if (!blocking.isEmpty()) {
            CalibrationReadinessStatus status = resolveBlockingStatus(blocking, operationalFlags);
            return buildAssessment(assessmentId, report, status, reasons, assessedAt);
        }

        reasons.add(CalibrationReadinessReason.ALL_CHECKS_PASSED);
        if (passesCanaryThresholds(report, policyConfig)) {
            return buildAssessment(
                    assessmentId,
                    report,
                    CalibrationReadinessStatus.READY_FOR_CONTROLLED_CANARY_REVIEW,
                    reasons,
                    assessedAt);
        }
        return buildAssessment(
                assessmentId,
                report,
                CalibrationReadinessStatus.READY_FOR_SHADOW_EXPANSION,
                reasons,
                assessedAt);
    }

    private static CalibrationReadinessStatus resolveBlockingStatus(
            Set<CalibrationReadinessReason> reasons, OperationalFlags operationalFlags) {
        if (reasons.contains(CalibrationReadinessReason.MIN_OBSERVATIONS)
                || reasons.contains(CalibrationReadinessReason.REPORT_INSUFFICIENT_DATA)) {
            return CalibrationReadinessStatus.INSUFFICIENT_DATA;
        }
        if (operationalFlags.regressionDetected()) {
            return CalibrationReadinessStatus.REGRESSION_DETECTED;
        }
        if (reasons.contains(CalibrationReadinessReason.FAIRNESS_REVIEW_REQUIRED)) {
            return CalibrationReadinessStatus.FAIRNESS_REVIEW_REQUIRED;
        }
        if (reasons.contains(CalibrationReadinessReason.PRIVACY_REVIEW_REQUIRED)) {
            return CalibrationReadinessStatus.PRIVACY_REVIEW_REQUIRED;
        }
        if (reasons.contains(CalibrationReadinessReason.OPERATIONAL_VERIFICATION_MISSING)) {
            return CalibrationReadinessStatus.OPERATIONALLY_UNVERIFIED;
        }
        if (reasons.contains(CalibrationReadinessReason.CALIBRATION_INCOMPLETE)) {
            return CalibrationReadinessStatus.CALIBRATION_INCOMPLETE;
        }
        if (reasons.contains(CalibrationReadinessReason.REPLAY_MISMATCH)
                || reasons.contains(CalibrationReadinessReason.METRIC_INSUFFICIENT_DATA)
                || reasons.contains(CalibrationReadinessReason.INCONCLUSIVE_EVIDENCE)) {
            return CalibrationReadinessStatus.INCONCLUSIVE;
        }
        return CalibrationReadinessStatus.NOT_READY;
    }

    private static boolean passesCanaryThresholds(CalibrationReport report, CalibrationPolicyConfig policyConfig) {
        OptionalInt precision = metricBasisPoints(report, CalibrationMetricType.PRECISION);
        OptionalInt recall = metricBasisPoints(report, CalibrationMetricType.RECALL);
        return precision.isPresent()
                && recall.isPresent()
                && precision.getAsInt() >= policyConfig.minimumPrecisionBasisPoints()
                && recall.getAsInt() >= policyConfig.minimumRecallBasisPoints();
    }

    private static boolean hasClassificationInsufficientData(CalibrationReport report) {
        return report.metric(CalibrationMetricType.PRECISION)
                        .map(metric -> metric.applicability() == CalibrationMetricApplicability.INSUFFICIENT_DATA)
                        .orElse(false)
                || report.metric(CalibrationMetricType.RECALL)
                        .map(metric -> metric.applicability() == CalibrationMetricApplicability.INSUFFICIENT_DATA)
                        .orElse(false);
    }

    private static OptionalInt metricBasisPoints(CalibrationReport report, CalibrationMetricType type) {
        Optional<CalibrationMetricValue> metric = report.metric(type);
        if (metric.isEmpty()) {
            return OptionalInt.empty();
        }
        return metric.get().ratioBasisPoints();
    }

    private static CalibrationReadinessAssessment buildAssessment(
            UUID assessmentId,
            CalibrationReport report,
            CalibrationReadinessStatus status,
            List<CalibrationReadinessReason> reasons,
            Instant assessedAt) {
        if (isAuthorityEnabling(status) && !reasons.contains(CalibrationReadinessReason.ALL_CHECKS_PASSED)) {
            throw new IllegalStateException("authority-enabling readiness requires all checks to pass");
        }
        String policyVersion = report.candidatePolicyVersion()
                .or(report::baselinePolicyVersion)
                .orElse(report.calibrationPolicyVersion());
        return new CalibrationReadinessAssessment(
                assessmentId,
                report.engineType(),
                policyVersion,
                report.reportId(),
                status,
                List.copyOf(reasons),
                assessedAt);
    }

    private static boolean isAuthorityEnabling(CalibrationReadinessStatus status) {
        return status == CalibrationReadinessStatus.READY_FOR_SHADOW_EXPANSION
                || status == CalibrationReadinessStatus.READY_FOR_CONTROLLED_CANARY_REVIEW;
    }
}