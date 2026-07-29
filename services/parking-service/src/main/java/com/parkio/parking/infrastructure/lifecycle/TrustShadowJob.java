package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.TrustShadowRowProcessor;
import com.parkio.parking.application.port.TrustShadowObserverPort;
import com.parkio.parking.application.port.ValidatedOutcomeForTrustReadPort;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
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
        name = "parkio.lifecycle.trust-shadow.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TrustShadowJob {

    private static final Logger log = LoggerFactory.getLogger(TrustShadowJob.class);

    private final ValidatedOutcomeForTrustReadPort outcomes;
    private final TrustShadowRowProcessor processor;
    private final TrustShadowObserverPort observer;
    private final int batchSize;

    public TrustShadowJob(
            ValidatedOutcomeForTrustReadPort outcomes,
            TrustShadowRowProcessor processor,
            TrustShadowObserverPort observer,
            @Value("${parkio.lifecycle.trust-shadow.batch-size:100}") int batchSize) {
        this.outcomes = outcomes;
        this.processor = processor;
        this.observer = observer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.trust-shadow.fixed-delay-ms:60000}")
    @Transactional
    public void processPendingOutcomes() {
        try {
            List<com.parkio.parking.application.trust.ValidatedOutcomeForTrust> batch =
                    outcomes.claimPendingReporterOutcomes(batchSize);
            observer.recordSchedulerCandidates(batch.size());
            int completed = 0;
            for (var candidate : batch) {
                TrustShadowProcessingResult result = processor.process(candidate);
                observer.recordProcessingResult(result);
                if (result.status() != TrustShadowProcessingResult.Status.FAILED) {
                    completed++;
                }
            }
            observer.recordSchedulerCompleted(completed);
        } catch (RuntimeException ex) {
            observer.recordSchedulerFailed();
            log.error("Trust shadow scheduler tick failed", ex);
        }
    }
}

