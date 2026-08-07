package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationOutcomeRecord;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationOutcomeType;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationPlatform;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationSnapshot;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RankingEvaluationJdbcRepository implements RankingEvaluationStore {

    private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RankingEvaluationJdbcRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void insertSnapshot(RankingEvaluationSnapshot snapshot) {
        jdbc.update(
                """
                INSERT INTO ranking_evaluations (
                    evaluation_id, created_at, expires_at, ranking_version, ranking_status,
                    shadow_ranker_version, feature_schema_version, candidate_count,
                    inventory_partial, inventory_composition, deterministic_order_json,
                    shadow_order_json, features_json, top1_agreement, top3_overlap
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshot.evaluationId(),
                Timestamp.from(snapshot.createdAt()),
                Timestamp.from(snapshot.expiresAt()),
                snapshot.rankingVersion(),
                snapshot.rankingStatus(),
                snapshot.shadowRankerVersion(),
                snapshot.featureSchemaVersion(),
                snapshot.candidateCount(),
                snapshot.inventoryPartial(),
                snapshot.inventoryComposition(),
                writeInts(snapshot.deterministicOrderByOrdinal()),
                snapshot.shadowOrderByOrdinal() == null
                        ? null
                        : writeInts(snapshot.shadowOrderByOrdinal()),
                snapshot.featuresJson(),
                snapshot.top1Agreement(),
                snapshot.top3Overlap());
    }

    @Override
    public void updateShadowOrder(
            UUID evaluationId,
            List<Integer> shadowOrderByOrdinal,
            Boolean top1Agreement,
            Integer top3Overlap,
            String shadowRankerVersion) {
        jdbc.update(
                """
                UPDATE ranking_evaluations
                   SET shadow_order_json = ?,
                       top1_agreement = ?,
                       top3_overlap = ?,
                       shadow_ranker_version = COALESCE(?, shadow_ranker_version)
                 WHERE evaluation_id = ?
                """,
                writeInts(shadowOrderByOrdinal),
                top1Agreement,
                top3Overlap,
                shadowRankerVersion,
                evaluationId);
    }

    @Override
    public Optional<RankingEvaluationSnapshot> findSnapshot(UUID evaluationId) {
        List<RankingEvaluationSnapshot> rows = jdbc.query(
                """
                SELECT evaluation_id, created_at, expires_at, ranking_version, ranking_status,
                       shadow_ranker_version, feature_schema_version, candidate_count,
                       inventory_partial, inventory_composition, deterministic_order_json,
                       shadow_order_json, features_json, top1_agreement, top3_overlap
                  FROM ranking_evaluations
                 WHERE evaluation_id = ?
                """,
                (rs, rowNum) -> mapSnapshot(rs),
                evaluationId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertOutcome(RankingEvaluationOutcomeRecord outcome) {
        try {
            jdbc.update(
                    """
                    INSERT INTO ranking_evaluation_outcomes (
                        evaluation_id, candidate_ordinal, outcome_type, occurred_at, platform, latency_bucket
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    outcome.evaluationId(),
                    outcome.candidateOrdinal(),
                    outcome.outcomeType().name(),
                    Timestamp.from(outcome.occurredAt()),
                    outcome.platform().name(),
                    outcome.latencyBucket());
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public List<RankingEvaluationSnapshot> listSnapshotsCreatedBetween(
            Instant fromInclusive, Instant toExclusive) {
        return jdbc.query(
                """
                SELECT evaluation_id, created_at, expires_at, ranking_version, ranking_status,
                       shadow_ranker_version, feature_schema_version, candidate_count,
                       inventory_partial, inventory_composition, deterministic_order_json,
                       shadow_order_json, features_json, top1_agreement, top3_overlap
                  FROM ranking_evaluations
                 WHERE created_at >= ? AND created_at < ?
                 ORDER BY created_at ASC
                """,
                (rs, rowNum) -> mapSnapshot(rs),
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive));
    }

    @Override
    public List<RankingEvaluationOutcomeRecord> listOutcomesForEvaluations(List<UUID> evaluationIds) {
        if (evaluationIds == null || evaluationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(evaluationIds.size(), "?"));
        Object[] args = evaluationIds.toArray();
        return jdbc.query(
                """
                SELECT evaluation_id, candidate_ordinal, outcome_type, occurred_at, platform, latency_bucket
                  FROM ranking_evaluation_outcomes
                 WHERE evaluation_id IN (%s)
                 ORDER BY occurred_at ASC
                """
                        .formatted(placeholders),
                (rs, rowNum) -> new RankingEvaluationOutcomeRecord(
                        (UUID) rs.getObject("evaluation_id"),
                        rs.getInt("candidate_ordinal"),
                        RankingEvaluationOutcomeType.valueOf(rs.getString("outcome_type")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        RankingEvaluationPlatform.valueOf(rs.getString("platform")),
                        rs.getString("latency_bucket")),
                args);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff, int batchSize) {
        return jdbc.update(
                """
                DELETE FROM ranking_evaluations
                 WHERE evaluation_id IN (
                    SELECT evaluation_id FROM ranking_evaluations
                     WHERE expires_at < ?
                     ORDER BY expires_at ASC
                     LIMIT ?
                 )
                """,
                Timestamp.from(cutoff),
                Math.max(1, batchSize));
    }

    private RankingEvaluationSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new RankingEvaluationSnapshot(
                (UUID) rs.getObject("evaluation_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("ranking_version"),
                rs.getString("ranking_status"),
                rs.getString("shadow_ranker_version"),
                rs.getString("feature_schema_version"),
                rs.getInt("candidate_count"),
                rs.getBoolean("inventory_partial"),
                rs.getString("inventory_composition"),
                readInts(rs.getString("deterministic_order_json")),
                readIntsNullable(rs.getString("shadow_order_json")),
                rs.getString("features_json"),
                (Boolean) rs.getObject("top1_agreement"),
                (Integer) rs.getObject("top3_overlap"));
    }

    private String writeInts(List<Integer> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize ordinal list", ex);
        }
    }

    private List<Integer> readInts(String json) {
        try {
            return objectMapper.readValue(json, INT_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to deserialize ordinal list", ex);
        }
    }

    private List<Integer> readIntsNullable(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return readInts(json);
    }
}
