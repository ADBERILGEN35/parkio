package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.TrustScoreEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustScoreJpaRepository extends JpaRepository<TrustScoreEntity, UUID> {
}
