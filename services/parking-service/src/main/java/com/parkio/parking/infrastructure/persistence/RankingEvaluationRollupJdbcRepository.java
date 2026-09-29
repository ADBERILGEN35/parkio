package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationRollupRecord;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationRollupStore;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RankingEvaluationRollupJdbcRepository implements RankingEvaluationRollupStore {

    private final JdbcTemplate jdbc;

    public RankingEvaluationRollupJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> findCompletedThrough() {
        List<Instant> rows = jdbc.query(
                "SELECT completed_through FROM ranking_evaluation_rollup_watermark WHERE id = 1",
                (rs, rowNum) -> rs.getTimestamp("completed_through").toInstant());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    @Transactional
    public void replaceSlice(
            Instant sliceStart,
            Instant sliceEnd,
            List<RankingEvaluationRollupRecord> rows,
            int evaluationsProcessed,
            int outcomesProcessed,
            Instant now) {
        jdbc.update(
                "DELETE FROM ranking_evaluation_daily_rollups WHERE rollup_hour = ?",
                Timestamp.from(sliceStart));
        if (rows != null) {
            for (RankingEvaluationRollupRecord row : rows) {
                insertRow(row);
            }
        }
        int written = rows == null ? 0 : rows.size();
        jdbc.update(
                """
                INSERT INTO ranking_evaluation_rollup_slices (
                    slice_start, slice_end, processed_at,
                    evaluations_processed, outcomes_processed, rollup_rows_written
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (slice_start) DO UPDATE SET
                    slice_end = EXCLUDED.slice_end,
                    processed_at = EXCLUDED.processed_at,
                    evaluations_processed = EXCLUDED.evaluations_processed,
                    outcomes_processed = EXCLUDED.outcomes_processed,
                    rollup_rows_written = EXCLUDED.rollup_rows_written
                """,
                Timestamp.from(sliceStart),
                Timestamp.from(sliceEnd),
                Timestamp.from(now),
                Math.max(0, evaluationsProcessed),
                Math.max(0, outcomesProcessed),
                written);
        jdbc.update(
                """
                UPDATE ranking_evaluation_rollup_watermark
                   SET completed_through = GREATEST(completed_through, ?),
                       updated_at = ?
                 WHERE id = 1
                """,
                Timestamp.from(sliceEnd),
                Timestamp.from(now));
    }

    @Override
    public List<RankingEvaluationRollupRecord> listRollupsBetween(
            Instant fromInclusive, Instant toExclusive) {
        return jdbc.query(
                """
                SELECT *
                  FROM ranking_evaluation_daily_rollups
                 WHERE rollup_hour >= ? AND rollup_hour < ?
                 ORDER BY rollup_hour ASC
                """,
                (rs, rowNum) -> new RankingEvaluationRollupRecord(
                        rs.getTimestamp("rollup_hour").toInstant(),
                        rs.getDate("rollup_date").toLocalDate(),
                        rs.getString("platform"),
                        rs.getString("inventory_composition"),
                        rs.getString("outcome_type"),
                        rs.getString("evidence_source"),
                        rs.getString("deterministic_ranking_version"),
                        rs.getString("shadow_ranker_version"),
                        rs.getString("feature_schema_version"),
                        rs.getString("evaluation_schema_version"),
                        rs.getString("candidate_count_bucket"),
                        rs.getString("freshness_mix"),
                        rs.getBoolean("zero_availability_present"),
                        rs.getBoolean("high_capacity_present"),
                        rs.getBoolean("partial"),
                        rs.getString("exposure_policy"),
                        rs.getLong("evaluation_count"),
                        rs.getLong("outcome_count"),
                        rs.getLong("shadow_attached_outcome_count"),
                        rs.getLong("deterministic_rank_sum"),
                        rs.getLong("shadow_rank_sum"),
                        rs.getLong("deterministic_top1_count"),
                        rs.getLong("deterministic_top3_count"),
                        rs.getLong("shadow_top1_count"),
                        rs.getLong("shadow_top3_count"),
                        rs.getLong("rank_delta_sum"),
                        rs.getLong("rank_delta_count"),
                        rs.getLong("delta_le_m3"),
                        rs.getLong("delta_m2"),
                        rs.getLong("delta_m1"),
                        rs.getLong("delta_0"),
                        rs.getLong("delta_p1"),
                        rs.getLong("delta_p2"),
                        rs.getLong("delta_ge_p3"),
                        rs.getLong("zero_availability_selected"),
                        rs.getLong("zero_availability_shadow_top1"),
                        rs.getLong("stale_static_present"),
                        rs.getLong("stale_static_selected"),
                        rs.getLong("stale_static_shadow_promoted")),
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive));
    }

    @Override
    public int deleteRollupsOlderThan(Instant cutoffHourExclusive, int batchSize) {
        return jdbc.update(
                """
                DELETE FROM ranking_evaluation_daily_rollups
                 WHERE rollup_hour IN (
                    SELECT rollup_hour FROM ranking_evaluation_daily_rollups
                     WHERE rollup_hour < ?
                     ORDER BY rollup_hour ASC
                     LIMIT ?
                 )
                """,
                Timestamp.from(cutoffHourExclusive),
                Math.max(1, batchSize));
    }

    @Override
    public int deleteExpiredRawBeforeWatermark(
            Instant expiresBefore, Instant createdBefore, int batchSize) {
        return jdbc.update(
                """
                DELETE FROM ranking_evaluations
                 WHERE evaluation_id IN (
                    SELECT evaluation_id FROM ranking_evaluations
                     WHERE expires_at < ?
                       AND created_at < ?
                     ORDER BY expires_at ASC
                     LIMIT ?
                 )
                """,
                Timestamp.from(expiresBefore),
                Timestamp.from(createdBefore),
                Math.max(1, batchSize));
    }

    private void insertRow(RankingEvaluationRollupRecord row) {
        jdbc.update(
                """
                INSERT INTO ranking_evaluation_daily_rollups (
                    rollup_hour, rollup_date, platform, inventory_composition, outcome_type,
                    evidence_source, deterministic_ranking_version, shadow_ranker_version,
                    feature_schema_version, evaluation_schema_version, candidate_count_bucket,
                    freshness_mix, zero_availability_present, high_capacity_present, partial,
                    exposure_policy,
                    evaluation_count, outcome_count, shadow_attached_outcome_count,
                    deterministic_rank_sum, shadow_rank_sum,
                    deterministic_top1_count, deterministic_top3_count,
                    shadow_top1_count, shadow_top3_count,
                    rank_delta_sum, rank_delta_count,
                    delta_le_m3, delta_m2, delta_m1, delta_0, delta_p1, delta_p2, delta_ge_p3,
                    zero_availability_selected, zero_availability_shadow_top1,
                    stale_static_present, stale_static_selected, stale_static_shadow_promoted
                ) VALUES (
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                )
                """,
                Timestamp.from(row.rollupHour()),
                Date.valueOf(row.rollupDate()),
                row.platform(),
                row.inventoryComposition(),
                row.outcomeType(),
                row.evidenceSource(),
                row.deterministicRankingVersion(),
                row.shadowRankerVersion(),
                row.featureSchemaVersion(),
                row.evaluationSchemaVersion(),
                row.candidateCountBucket(),
                row.freshnessMix(),
                row.zeroAvailabilityPresent(),
                row.highCapacityPresent(),
                row.partial(),
                row.exposurePolicy(),
                row.evaluationCount(),
                row.outcomeCount(),
                row.shadowAttachedOutcomeCount(),
                row.deterministicRankSum(),
                row.shadowRankSum(),
                row.deterministicTop1Count(),
                row.deterministicTop3Count(),
                row.shadowTop1Count(),
                row.shadowTop3Count(),
                row.rankDeltaSum(),
                row.rankDeltaCount(),
                row.deltaLeM3(),
                row.deltaM2(),
                row.deltaM1(),
                row.delta0(),
                row.deltaP1(),
                row.deltaP2(),
                row.deltaGeP3(),
                row.zeroAvailabilitySelected(),
                row.zeroAvailabilityShadowTop1(),
                row.staleStaticPresent(),
                row.staleStaticSelected(),
                row.staleStaticShadowPromoted());
    }
}
