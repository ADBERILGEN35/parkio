package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.infrastructure.persistence.entity.UserViolationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserViolationJpaRepository extends JpaRepository<UserViolationEntity, UUID> {
}
