package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileJpaRepository extends JpaRepository<UserProfileEntity, UUID> {

    Optional<UserProfileEntity> findByAuthUserId(UUID authUserId);

    boolean existsByAuthUserId(UUID authUserId);
}
