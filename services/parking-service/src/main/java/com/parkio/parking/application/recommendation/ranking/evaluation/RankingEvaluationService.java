package com.parkio.parking.application.recommendation.ranking.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowCandidateFeatures;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowFeatureExtractor;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingConstants;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingRequest;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Privacy-safe ranking evaluation correlation (WP-SPA-14B).
 *
 * <p>Never blocks public recommendation success. Never persists identity or location.
 */
@Service
public class RankingEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RankingEvaluationService.class);

    private final RankingEvaluationProperties properties;
    private final RankingEvaluationStore store;
    private final RankingEvaluationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RankingEvaluationService(
            RankingEvaluationProperties properties,
            RankingEvaluationStore store,
            RankingEvaluationMetrics metrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isEnabled() {
        return properties.snapshot().enabled();
    }

    /**
     * Mint evaluation id and persist privacy-safe snapshot. Fail-open: returns null on persistence
     * failure so public recommendation continues without evaluationId.
     */
    public UUID maybeCreateEvaluation(
            List<ParkingCandidate> publicOrdered,
            RankingVersion rankingVersion,
            RankingStatus rankingStatus,
            boolean inventoryPartial,
            int radiusMeters) {
        RankingEvaluationProperties.EvaluationConfiguration config = properties.snapshot();
        if (!config.enabled()) {
            return null;
        }
        if (publicOrdered == null || publicOrdered.isEmpty()) {
            return null;
        }
        try {
            UUID evaluationId = UUID.randomUUID();
            Instant createdAt = clock.instant();
            Instant expiresAt = createdAt.plus(Duration.ofHours(config.retentionHours()));

            ShadowRankingRequest request =
                    ShadowFeatureExtractor.extract(publicOrdered, inventoryPartial, radiusMeters);
            List<Integer> deterministicOrder = new ArrayList<>(request.candidates().size());
            for (ShadowCandidateFeatures feature : request.candidates()) {
                deterministicOrder.add(feature.candidateOrdinal());
            }
            String featuresJson = objectMapper.writeValueAsString(request.candidates());
            RankingEvaluationPrivacyGuard.assertFeaturesJsonAllowed(objectMapper, featuresJson);
            RankingEvaluationPrivacyGuard.assertNoForbiddenFields(objectMapper, featuresJson);
            String deterministicJson = objectMapper.writeValueAsString(deterministicOrder);
            RankingEvaluationPrivacyGuard.assertOrdinalListJson(
                    objectMapper, deterministicJson, deterministicOrder.size());

            RankingEvaluationSnapshot snapshot = new RankingEvaluationSnapshot(
                    evaluationId,
                    createdAt,
                    expiresAt,
                    rankingVersion == null ? RankingVersion.DISTANCE_BASELINE_V1.name() : rankingVersion.name(),
                    rankingStatus == null ? RankingStatus.DISABLED.name() : rankingStatus.name(),
                    null,
                    ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                    deterministicOrder.size(),
                    inventoryPartial,
                    request.inventoryComposition().name(),
                    deterministicOrder,
                    null,
                    featuresJson,
                    null,
                    null);
            store.insertSnapshot(snapshot);
            metrics.recordCreated();
            log.info(
                    "evaluation_created status=ok candidateCountBucket={} inventoryComposition={}",
                    candidateCountBucket(deterministicOrder.size()),
                    request.inventoryComposition().name());
            return evaluationId;
        } catch (RuntimeException ex) {
            metrics.recordPersistenceFailed(ex.getClass().getSimpleName());
            log.warn(
                    "evaluation_created status=persistence_failed type={}",
                    ex.getClass().getSimpleName());
            return null;
        } catch (Exception ex) {
            metrics.recordPersistenceFailed(ex.getClass().getSimpleName());
            log.warn(
                    "evaluation_created status=persistence_failed type={}",
                    ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Attach shadow ordinal order after async challenger succeeds. Fail-open. */
    public void maybeAttachShadowOrder(
            UUID evaluationId,
            List<String> authoritativeAliases,
            List<String> shadowAliases,
            Boolean top1Agreement,
            Integer top3Overlap,
            String shadowRankerVersion) {
        if (evaluationId == null || !isEnabled()) {
            return;
        }
        if (authoritativeAliases == null
                || shadowAliases == null
                || authoritativeAliases.isEmpty()
                || shadowAliases.isEmpty()) {
            return;
        }
        try {
            Map<String, Integer> aliasToOrdinal = new HashMap<>();
            for (int i = 0; i < authoritativeAliases.size(); i++) {
                aliasToOrdinal.put(authoritativeAliases.get(i), i);
            }
            List<Integer> shadowOrder = new ArrayList<>(shadowAliases.size());
            for (String alias : shadowAliases) {
                Integer ordinal = aliasToOrdinal.get(alias);
                if (ordinal == null) {
                    return;
                }
                shadowOrder.add(ordinal);
            }
            store.updateShadowOrder(
                    evaluationId, shadowOrder, top1Agreement, top3Overlap, shadowRankerVersion);
            metrics.recordShadowOrderUpdated();
        } catch (RuntimeException ex) {
            metrics.recordPersistenceFailed("shadow_order_" + ex.getClass().getSimpleName());
            log.debug(
                    "evaluation shadow order update failed type={}",
                    ex.getClass().getSimpleName());
        }
    }

    public OutcomeWriteResult recordOutcome(
            UUID evaluationId,
            int candidateOrdinal,
            RankingEvaluationOutcomeType outcomeType,
            RankingEvaluationPlatform platform,
            String latencyBucket) {
        if (!isEnabled()) {
            return OutcomeWriteResult.DISABLED;
        }
        if (evaluationId == null || outcomeType == null) {
            metrics.recordOutcomeRejected("invalid");
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME);
        }
        if (candidateOrdinal < 0) {
            metrics.recordOutcomeRejected("ordinal");
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME);
        }
        Instant now = clock.instant();
        Optional<RankingEvaluationSnapshot> snapshot;
        try {
            snapshot = store.findSnapshot(evaluationId);
        } catch (RuntimeException ex) {
            metrics.recordOutcomePersistenceFailed(ex.getClass().getSimpleName());
            return OutcomeWriteResult.PERSISTENCE_FAILED;
        }
        if (snapshot.isEmpty()) {
            metrics.recordOutcomeRejected("not_found");
            throw new ParkingException(ParkingErrorCode.RANKING_EVALUATION_NOT_FOUND);
        }
        RankingEvaluationSnapshot row = snapshot.get();
        if (!now.isBefore(row.expiresAt())) {
            metrics.recordOutcomeExpired();
            throw new ParkingException(ParkingErrorCode.RANKING_EVALUATION_EXPIRED);
        }
        if (candidateOrdinal >= row.candidateCount()) {
            metrics.recordOutcomeRejected("ordinal_bounds");
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME);
        }
        RankingEvaluationOutcomeRecord record = new RankingEvaluationOutcomeRecord(
                evaluationId,
                candidateOrdinal,
                outcomeType,
                now,
                platform == null ? RankingEvaluationPlatform.UNKNOWN : platform,
                sanitizeLatencyBucket(latencyBucket));
        try {
            boolean inserted = store.insertOutcome(record);
            if (!inserted) {
                metrics.recordOutcomeDuplicate(outcomeType.name());
                return OutcomeWriteResult.DUPLICATE;
            }
            metrics.recordOutcomeRecorded(
                    outcomeType.name(),
                    record.platform().name());
            log.info(
                    "evaluation_outcome status=recorded outcomeType={} platform={}",
                    outcomeType.name(),
                    record.platform().name());
            return OutcomeWriteResult.RECORDED;
        } catch (RuntimeException ex) {
            metrics.recordOutcomePersistenceFailed(ex.getClass().getSimpleName());
            log.warn(
                    "evaluation_outcome status=persistence_failed type={}",
                    ex.getClass().getSimpleName());
            return OutcomeWriteResult.PERSISTENCE_FAILED;
        }
    }

    public int cleanupExpired() {
        RankingEvaluationProperties.EvaluationConfiguration config = properties.snapshot();
        if (!config.cleanupEnabled()) {
            return 0;
        }
        try {
            int deleted = store.deleteExpiredBefore(clock.instant(), config.cleanupBatchSize());
            metrics.recordCleanupDeleted(deleted);
            if (deleted > 0) {
                log.info("evaluation_cleanup status=ok deletedBucket={}", deletedBucket(deleted));
            }
            return deleted;
        } catch (RuntimeException ex) {
            log.warn(
                    "evaluation_cleanup status=failed type={}",
                    ex.getClass().getSimpleName());
            return 0;
        }
    }

    static String candidateCountBucket(int count) {
        if (count <= 0) {
            return "0";
        }
        if (count <= 3) {
            return "1_3";
        }
        if (count <= 10) {
            return "4_10";
        }
        return "11_plus";
    }

    static String deletedBucket(int deleted) {
        if (deleted <= 10) {
            return "1_10";
        }
        if (deleted <= 100) {
            return "11_100";
        }
        return "100_plus";
    }

    static String sanitizeLatencyBucket(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim()) {
            case "0_5s", "5_30s", "30s_5m", "5m_plus" -> raw.trim();
            default -> null;
        };
    }

    public enum OutcomeWriteResult {
        RECORDED,
        DUPLICATE,
        PERSISTENCE_FAILED,
        DISABLED
    }
}
