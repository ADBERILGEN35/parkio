package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.ContinuousCalibrationRowProcessor;
import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import com.parkio.parking.application.port.ContinuousCalibrationObserverPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = "parkio.lifecycle.calibration.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ContinuousCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(ContinuousCalibrationJob.class);

    private final ContinuousCalibrationRowProcessor processor;
    private final ContinuousCalibrationObserverPort observer;
    private final int batchSize;

    public ContinuousCalibrationJob(
            ContinuousCalibrationRowProcessor processor,
            ContinuousCalibrationObserverPort observer,
            @Value("${parkio.lifecycle.calibration.batch-size:100}") int batchSize) {
        this.processor = processor;
        this.observer = observer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.calibration.fixed-delay-ms:60000}")
    @Transactional
    public void processPendingCalibration() {
        try {
            CalibrationProcessingResult trustResult = processor.processTrustBatch(batchSize);
            observer.recordProcessingResult(trustResult);

            CalibrationProcessingResult fraudResult = processor.processFraudBatch(batchSize);
            observer.recordProcessingResult(fraudResult);
        } catch (RuntimeException ex) {
            observer.recordSchedulerFailed();
            log.error("Continuous calibration scheduler tick failed", ex);
        }
    }
}
