package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.PendingRewardLedgerEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingRewardLedgerJpaRepository extends JpaRepository<PendingRewardLedgerEntity, UUID> {

    Optional<PendingRewardLedgerEntity> findByEvaluationId(UUID evaluationId);

    List<PendingRewardLedgerEntity> findByRewardSubjectTypeAndRewardSubjectIdOrderByEvaluatedAtAscIdAsc(
            String rewardSubjectType,
            UUID rewardSubjectId);

    Optional<PendingRewardLedgerEntity> findTopBySourceContributionIdOrderByEvaluatedAtDescIdDesc(UUID sourceContributionId);
}
