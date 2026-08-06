package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.FavouriteDestinationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavouriteDestinationJpaRepository extends JpaRepository<FavouriteDestinationEntity, UUID> {

    Optional<FavouriteDestinationEntity> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<FavouriteDestinationEntity> findByUserProfileIdAndDuplicateKey(UUID userProfileId, String duplicateKey);

    List<FavouriteDestinationEntity> findByUserProfileIdOrderByUpdatedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);
}
