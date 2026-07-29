package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.FraudLedgerEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudLedgerJpaRepository extends JpaRepository<FraudLedgerEntity, UUID> {

    Optional<FraudLedgerEntity> findByEvaluationId(UUID evaluationId);

    List<FraudLedgerEntity> findBySubjectTypeAndSubjectIdOrderByEvaluatedAtAscIdAsc(String subjectType, UUID subjectId);
}
