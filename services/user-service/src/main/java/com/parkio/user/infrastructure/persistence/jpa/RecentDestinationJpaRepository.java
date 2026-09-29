package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.RecentDestinationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecentDestinationJpaRepository extends JpaRepository<RecentDestinationEntity, UUID> {

    Optional<RecentDestinationEntity> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<RecentDestinationEntity> findByUserProfileIdAndDuplicateKey(UUID userProfileId, String duplicateKey);

    List<RecentDestinationEntity> findByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    void deleteByUserProfileId(UUID userProfileId);
}
