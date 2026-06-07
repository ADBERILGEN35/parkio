package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserTrustProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTrustProfileJpaRepository extends JpaRepository<UserTrustProfileEntity, UUID> {

    Optional<UserTrustProfileEntity> findByUserProfileId(UUID userProfileId);
}
