package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.TrustLedgerEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustLedgerJpaRepository extends JpaRepository<TrustLedgerEntity, UUID> {

    Optional<TrustLedgerEntity> findByEvaluationId(UUID evaluationId);

    List<TrustLedgerEntity> findBySubjectTypeAndSubjectIdOrderByEvaluatedAtAscIdAsc(String subjectType, UUID subjectId);
}

