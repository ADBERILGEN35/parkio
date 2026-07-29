package com.parkio.parking.application.port;

import com.parkio.parking.application.calibration.CalibrationFailureStage;
import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.calibration.CalibrationReplayComparison;
import com.parkio.parking.calibration.CalibrationReport;
import java.time.Duration;

/** Observability boundary for continuous calibration processing. */
public interface ContinuousCalibrationObserverPort {

    static ContinuousCalibrationObserverPort noop() {
        return new ContinuousCalibrationObserverPort() {
            @Override public void recordSchedulerCandidates(CalibrationEngineType engineType, int count) {}
            @Override public void recordCandidateReceived(CalibrationEngineType engineType) {}
            @Override public void recordObservationAppended(CalibrationEngineType engineType) {}
            @Override public void recordObservationDuplicate(CalibrationEngineType engineType) {}
            @Override public void recordObservationFailure(CalibrationEngineType engineType, CalibrationFailureStage stage) {}
            @Override public void recordReportGenerated(CalibrationReport report, Duration duration) {}
            @Override public void recordReportDuplicate(CalibrationEngineType engineType) {}
            @Override public void recordReportFailure(CalibrationEngineType engineType, CalibrationFailureStage stage) {}
            @Override public void recordReadinessAssessed(CalibrationReadinessAssessment assessment) {}
            @Override public void recordReplaySuccess(CalibrationReplayComparison comparison) {}
            @Override public void recordReplayMismatch(CalibrationReplayComparison comparison) {}
            @Override public void recordReplayFailure(CalibrationEngineType engineType) {}
            @Override public void recordSchedulerCompleted(CalibrationEngineType engineType, int completed) {}
            @Override public void recordSchedulerFailed() {}
            @Override public void recordProcessingResult(CalibrationProcessingResult result) {}
        };
    }

    void recordSchedulerCandidates(CalibrationEngineType engineType, int count);

    void recordCandidateReceived(CalibrationEngineType engineType);

    void recordObservationAppended(CalibrationEngineType engineType);

    void recordObservationDuplicate(CalibrationEngineType engineType);

    void recordObservationFailure(CalibrationEngineType engineType, CalibrationFailureStage stage);

    void recordReportGenerated(CalibrationReport report, Duration duration);

    void recordReportDuplicate(CalibrationEngineType engineType);

    void recordReportFailure(CalibrationEngineType engineType, CalibrationFailureStage stage);

    void recordReadinessAssessed(CalibrationReadinessAssessment assessment);

    void recordReplaySuccess(CalibrationReplayComparison comparison);

    void recordReplayMismatch(CalibrationReplayComparison comparison);

    void recordReplayFailure(CalibrationEngineType engineType);

    void recordSchedulerCompleted(CalibrationEngineType engineType, int completed);

    void recordSchedulerFailed();

    void recordProcessingResult(CalibrationProcessingResult result);
}
