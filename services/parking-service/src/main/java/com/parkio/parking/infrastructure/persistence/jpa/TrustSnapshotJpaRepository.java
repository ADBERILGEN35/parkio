package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustSnapshotJpaRepository extends JpaRepository<TrustSnapshotEntity, UUID> {

    Optional<TrustSnapshotEntity> findBySubjectTypeAndSubjectIdAndTrustDomain(
            String subjectType,
            UUID subjectId,
            String trustDomain);
}

