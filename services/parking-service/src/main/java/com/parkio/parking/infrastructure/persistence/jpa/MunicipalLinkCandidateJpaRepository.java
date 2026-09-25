package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.MunicipalLinkCandidateEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MunicipalLinkCandidateJpaRepository
        extends JpaRepository<MunicipalLinkCandidateEntity, UUID> {
    Page<MunicipalLinkCandidateEntity> findByReviewState(String reviewState, Pageable pageable);

    Optional<MunicipalLinkCandidateEntity>
            findBySourceKeyAAndExternalIdAAndSourceKeyBAndExternalIdBAndSourceVersionAAndSourceVersionBAndAlgorithmVersion(
                    String sourceKeyA,
                    String externalIdA,
                    String sourceKeyB,
                    String externalIdB,
                    String sourceVersionA,
                    String sourceVersionB,
                    String algorithmVersion);
}
