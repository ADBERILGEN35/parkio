package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserTrustScoreHistoryEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTrustScoreHistoryJpaRepository extends JpaRepository<UserTrustScoreHistoryEntity, UUID> {
}
