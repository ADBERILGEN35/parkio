package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.OutcomeHistoryEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutcomeHistoryJpaRepository extends JpaRepository<OutcomeHistoryEntity, UUID> {

    Optional<OutcomeHistoryEntity> findTopByParkingSpotIdOrderByEvaluatedAtDescIdDesc(UUID parkingSpotId);

    Optional<OutcomeHistoryEntity> findTopByParkingSpotIdAndEvaluatedAtLessThanEqualOrderByEvaluatedAtDescIdDesc(
            UUID parkingSpotId,
            Instant cutoffInclusive);

    Optional<OutcomeHistoryEntity> findByEvaluationId(UUID evaluationId);

    List<OutcomeHistoryEntity> findByParkingSpotIdOrderByEvaluatedAtAscIdAsc(UUID parkingSpotId);

    @Query(
            value = """
                    SELECT oh.id AS id, oh.parking_spot_id AS parkingSpotId, ps.owner_user_id AS ownerUserId
                    FROM outcome_history oh
                    JOIN parking_spots ps ON ps.id = oh.parking_spot_id
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM trust_ledger tl
                        WHERE tl.source_outcome_record_id = oh.id
                          AND tl.subject_type = 'REPORTER'
                          AND tl.subject_id = ps.owner_user_id
                          AND tl.trust_domain = 'PARKING_REPORT_ACCURACY'
                          AND tl.trust_policy_version = 'trust-policy-v1'
                    )
                    ORDER BY oh.evaluated_at ASC, oh.id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<ValidatedOutcomeForTrustRow> claimPendingReporterOutcomes(@Param("limit") int limit);

    @Query(
            value = """
                    SELECT oh.id AS id, oh.parking_spot_id AS parkingSpotId, ps.owner_user_id AS ownerUserId
                    FROM outcome_history oh
                    JOIN parking_spots ps ON ps.id = oh.parking_spot_id
                    WHERE oh.classification IN ('CONFIRMED_CORRECT', 'CONFIRMED_INCORRECT', 'EXPIRED_WITHOUT_EVIDENCE')
                      AND NOT EXISTS (
                        SELECT 1
                        FROM pending_reward_ledger prl
                        WHERE prl.source_parking_spot_id = oh.parking_spot_id
                          AND prl.reward_subject_type = 'USER'
                          AND prl.reward_subject_id = ps.owner_user_id
                          AND prl.contribution_role = 'REPORTER'
                          AND prl.reward_policy_version = 'reward-policy-v1'
                      )
                    ORDER BY oh.evaluated_at ASC, oh.id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<ValidatedOutcomeForRewardRow> claimPendingReporterRewards(@Param("limit") int limit);

    @Query(
            value = """
                    SELECT oh.id AS id, oh.parking_spot_id AS parkingSpotId, ps.owner_user_id AS ownerUserId
                    FROM outcome_history oh
                    JOIN parking_spots ps ON ps.id = oh.parking_spot_id
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM fraud_evaluation_ledger fel
                        WHERE fel.source_outcome_record_id = oh.id
                          AND fel.subject_type = 'USER'
                          AND fel.subject_id = ps.owner_user_id
                          AND fel.fraud_domain = 'CONTRIBUTION_INTEGRITY'
                          AND fel.policy_version = 'fraud-policy-v1'
                    )
                    ORDER BY oh.evaluated_at ASC, oh.id ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<ValidatedOutcomeForTrustRow> claimPendingReporterFraudCandidates(@Param("limit") int limit);
}