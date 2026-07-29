package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.RewardShadowRowProcessor;
import com.parkio.parking.application.port.RewardShadowObserverPort;
import com.parkio.parking.application.port.ValidatedOutcomeForRewardReadPort;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
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
        name = "parkio.lifecycle.reward-shadow.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class RewardShadowJob {

    private static final Logger log = LoggerFactory.getLogger(RewardShadowJob.class);

    private final ValidatedOutcomeForRewardReadPort outcomes;
    private final RewardShadowRowProcessor processor;
    private final RewardShadowObserverPort observer;
    private final int batchSize;

    public RewardShadowJob(
            ValidatedOutcomeForRewardReadPort outcomes,
            RewardShadowRowProcessor processor,
            RewardShadowObserverPort observer,
            @Value("${parkio.lifecycle.reward-shadow.batch-size:100}") int batchSize) {
        this.outcomes = outcomes;
        this.processor = processor;
        this.observer = observer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.reward-shadow.fixed-delay-ms:60000}")
    @Transactional
    public void processPendingOutcomes() {
        try {
            List<com.parkio.parking.application.reward.ValidatedOutcomeForReward> batch =
                    outcomes.claimPendingReporterOutcomes(batchSize);
            observer.recordSchedulerCandidates(batch.size());
            int completed = 0;
            for (var candidate : batch) {
                RewardShadowProcessingResult result = processor.process(candidate);
                observer.recordProcessingResult(result);
                if (result.status() != RewardShadowProcessingResult.Status.FAILED) {
                    completed++;
                }
            }
            observer.recordSchedulerCompleted(completed);
        } catch (RuntimeException ex) {
            observer.recordSchedulerFailed();
            log.error("Reward shadow scheduler tick failed", ex);
        }
    }
}
