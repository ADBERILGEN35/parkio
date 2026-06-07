package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserPreferenceEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceJpaRepository extends JpaRepository<UserPreferenceEntity, UUID> {

    Optional<UserPreferenceEntity> findByUserProfileId(UUID userProfileId);
}
