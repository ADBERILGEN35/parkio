package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.calibration.CalibrationFailureStage;
import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import com.parkio.parking.application.port.ContinuousCalibrationObserverPort;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.calibration.CalibrationReplayComparison;
import com.parkio.parking.calibration.CalibrationReport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for continuous calibration observability.
 * Tags are bounded enums only - never spot/evaluation/cohort values.
 */
@Component
public class ContinuousCalibrationMetrics implements ContinuousCalibrationObserverPort {

    private final MeterRegistry registry;
    private final Timer reportGenerationDuration;

    public ContinuousCalibrationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reportGenerationDuration = Timer.builder("parkio.parking.calibration.report.duration")
                .description("Continuous calibration report generation duration")
                .register(registry);
    }

    @Override
    public void recordSchedulerCandidates(CalibrationEngineType engineType, int count) {
        registry.summary("parkio.parking.calibration.scheduler.candidates", engineTag(engineType))
                .record(count);
    }

    @Override
    public void recordCandidateReceived(CalibrationEngineType engineType) {
        registry.counter("parkio.parking.calibration.candidate.received", engineTag(engineType)).increment();
    }

    @Override
    public void recordObservationAppended(CalibrationEngineType engineType) {
        registry.counter("parkio.parking.calibration.observation.success", engineTag(engineType)).increment();
        registry.counter("parkio.parking.calibration.observation.appended", engineTag(engineType)).increment();
    }

    @Override
    public void recordObservationDuplicate(CalibrationEngineType engineType) {
        registry.counter("parkio.parking.calibration.observation.duplicate", engineTag(engineType)).increment();
    }

    @Override
    public void recordObservationFailure(CalibrationEngineType engineType, CalibrationFailureStage stage) {
        registry.counter(
                        "parkio.parking.calibration.observation.failure",
                        engineTag(engineType).and("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordReportGenerated(CalibrationReport report, Duration duration) {
        registry.counter(
                        "parkio.parking.calibration.report.generated",
                        engineTag(report.engineType()).and("report_status", report.reportStatus().name()))
                .increment();
        reportGenerationDuration.record(duration);
    }

    @Override
    public void recordReportDuplicate(CalibrationEngineType engineType) {
        registry.counter("parkio.parking.calibration.report.duplicate", engineTag(engineType)).increment();
    }

    @Override
    public void recordReportFailure(CalibrationEngineType engineType, CalibrationFailureStage stage) {
        registry.counter(
                        "parkio.parking.calibration.report.failure",
                        engineTag(engineType).and("failure_stage", stage.name()))
                .increment();
    }

    @Override
    public void recordReadinessAssessed(CalibrationReadinessAssessment assessment) {
        registry.counter(
                        "parkio.parking.calibration.readiness.assessed",
                        engineTag(assessment.engineType())
                                .and("readiness_status", assessment.readinessStatus().name()))
                .increment();
    }

    @Override
    public void recordReplaySuccess(CalibrationReplayComparison comparison) {
        registry.counter(
                        "parkio.parking.calibration.replay.success",
                        engineTag(comparison.original().engineType()))
                .increment();
    }

    @Override
    public void recordReplayMismatch(CalibrationReplayComparison comparison) {
        registry.counter(
                        "parkio.parking.calibration.replay.mismatch",
                        engineTag(comparison.original().engineType()))
                .increment();
    }

    @Override
    public void recordReplayFailure(CalibrationEngineType engineType) {
        registry.counter("parkio.parking.calibration.replay.failure", engineTag(engineType)).increment();
    }

    @Override
    public void recordSchedulerCompleted(CalibrationEngineType engineType, int completed) {
        registry.counter("parkio.parking.calibration.scheduler.completed", engineTag(engineType))
                .increment(completed);
    }

    @Override
    public void recordSchedulerFailed() {
        registry.counter("parkio.parking.calibration.scheduler.failed").increment();
    }

    @Override
    public void recordProcessingResult(CalibrationProcessingResult result) {
        registry.counter(
                        "parkio.parking.calibration.processing.result",
                        engineTag(result.engineType()).and("status", result.status().name()))
                .increment();
    }

    private static Tags engineTag(CalibrationEngineType engineType) {
        return Tags.of("engine_type", engineType.name());
    }
}