package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.OutcomeValidationApplicationService;
import com.parkio.parking.application.outcome.OutcomeProcessingResult;
import com.parkio.parking.application.port.OutcomeEvaluationTriggerPort;
import com.parkio.parking.application.port.OutcomeOperationalizationObserverPort;
import java.time.Clock;
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
        name = "parkio.lifecycle.outcome-validation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutcomeValidationTriggerJob {

    private static final Logger log = LoggerFactory.getLogger(OutcomeValidationTriggerJob.class);

    private final OutcomeEvaluationTriggerPort triggers;
    private final OutcomeValidationApplicationService service;
    private final OutcomeOperationalizationObserverPort observer;
    private final Clock clock;
    private final int batchSize;

    public OutcomeValidationTriggerJob(
            OutcomeEvaluationTriggerPort triggers,
            OutcomeValidationApplicationService service,
            OutcomeOperationalizationObserverPort observer,
            Clock clock,
            @Value("${parkio.lifecycle.outcome-validation.batch-size:100}") int batchSize) {
        this.triggers = triggers;
        this.service = service;
        this.observer = observer;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.outcome-validation.fixed-delay-ms:60000}")
    @Transactional
    public void processPendingTriggers() {
        try {
            List<com.parkio.parking.application.outcome.OutcomeEvaluationTriggerRequest> batch = triggers.claimPendingBatch(batchSize);
            observer.recordSchedulerCandidates(batch.size());
            int completed = 0;
            for (var trigger : batch) {
                OutcomeProcessingResult result = service.process(trigger);
                switch (result.status()) {
                    case APPENDED, DUPLICATE, INELIGIBLE -> {
                        triggers.markProcessed(trigger, clock.instant());
                        completed++;
                    }
                    case FAILED -> triggers.recordFailure(
                            trigger,
                            result.failureStage().map(Enum::name).orElse("UNKNOWN"),
                            clock.instant());
                }
            }
            observer.recordSchedulerCompleted(completed);
        } catch (RuntimeException ex) {
            observer.recordSchedulerFailed();
            log.error("Outcome trigger scheduler tick failed", ex);
        }
    }
}