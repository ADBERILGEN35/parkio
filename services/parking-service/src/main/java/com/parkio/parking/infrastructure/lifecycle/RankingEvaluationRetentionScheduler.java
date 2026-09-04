package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationProperties;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batched expiry cleanup for privacy-safe ranking evaluation rows (WP-SPA-14B).
 */
@Component
@ConditionalOnProperty(
        prefix = "parkio.spa.ranking.evaluation",
        name = "cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RankingEvaluationRetentionScheduler {

    private final RankingEvaluationService evaluationService;
    private final RankingEvaluationProperties properties;

    public RankingEvaluationRetentionScheduler(
            RankingEvaluationService evaluationService, RankingEvaluationProperties properties) {
        this.evaluationService = evaluationService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${parkio.spa.ranking.evaluation.cleanup-fixed-delay-ms:900000}")
    public void cleanupExpired() {
        if (!properties.isEnabled() && !properties.isCleanupEnabled()) {
            return;
        }
        evaluationService.cleanupExpired();
    }
}
