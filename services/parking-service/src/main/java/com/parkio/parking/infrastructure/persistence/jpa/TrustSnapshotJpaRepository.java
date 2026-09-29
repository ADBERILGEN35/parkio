package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface TrustSnapshotJpaRepository extends JpaRepository<TrustSnapshotEntity, UUID> {

    Optional<TrustSnapshotEntity> findBySubjectTypeAndSubjectIdAndTrustDomain(
            String subjectType,
            UUID subjectId,
            String trustDomain);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT snapshot FROM TrustSnapshotEntity snapshot
            WHERE snapshot.subjectType = :subjectType
              AND snapshot.subjectId = :subjectId
              AND snapshot.trustDomain = :trustDomain
            """)
    Optional<TrustSnapshotEntity> lockBySubjectTypeAndSubjectIdAndTrustDomain(
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("trustDomain") String trustDomain);
}

