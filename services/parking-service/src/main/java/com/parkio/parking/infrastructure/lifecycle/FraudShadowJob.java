package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.FraudShadowRowProcessor;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.port.FraudShadowObserverPort;
import com.parkio.parking.application.port.ValidatedOutcomeForFraudReadPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = "parkio.lifecycle.fraud-shadow.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class FraudShadowJob {

    private static final Logger log = LoggerFactory.getLogger(FraudShadowJob.class);

    private final ValidatedOutcomeForFraudReadPort outcomes;
    private final FraudShadowRowProcessor processor;
    private final FraudShadowObserverPort observer;
    private final int batchSize;

    public FraudShadowJob(
            ValidatedOutcomeForFraudReadPort outcomes,
            FraudShadowRowProcessor processor,
            FraudShadowObserverPort observer,
            @Value("${parkio.lifecycle.fraud-shadow.batch-size:100}") int batchSize) {
        this.outcomes = outcomes;
        this.processor = processor;
        this.observer = observer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.fraud-shadow.fixed-delay-ms:60000}")
    @Transactional
    public void processPendingOutcomes() {
        try {
            List<com.parkio.parking.application.fraud.ValidatedOutcomeForFraud> batch =
                    outcomes.claimPendingReporterFraudCandidates(batchSize);
            observer.recordSchedulerCandidates(batch.size());
            int completed = 0;
            for (var candidate : batch) {
                FraudShadowProcessingResult result = processor.process(candidate);
                observer.recordProcessingResult(result);
                if (result.status() != FraudShadowProcessingResult.Status.FAILED) {
                    completed++;
                }
            }
            observer.recordSchedulerCompleted(completed);
        } catch (RuntimeException ex) {
            observer.recordSchedulerFailed();
            log.error("Fraud shadow scheduler tick failed", ex);
        }
    }
}
