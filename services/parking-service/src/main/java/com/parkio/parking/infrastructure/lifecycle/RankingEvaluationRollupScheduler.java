package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationProperties;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationRollupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closed-hour privacy-safe ranking evaluation rollups + aggregate retention (WP-SPA-14D).
 */
@Component
@ConditionalOnProperty(
        prefix = "parkio.spa.ranking.evaluation",
        name = "rollup-enabled",
        havingValue = "true")
public class RankingEvaluationRollupScheduler {

    private final RankingEvaluationRollupService rollupService;
    private final RankingEvaluationProperties properties;

    public RankingEvaluationRollupScheduler(
            RankingEvaluationRollupService rollupService, RankingEvaluationProperties properties) {
        this.rollupService = rollupService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${parkio.spa.ranking.evaluation.rollup-fixed-delay-ms:900000}")
    public void rollupClosedSlices() {
        if (!properties.isRollupEnabled()) {
            return;
        }
        rollupService.runRollupPass();
        rollupService.cleanupOldRollups();
    }
}
