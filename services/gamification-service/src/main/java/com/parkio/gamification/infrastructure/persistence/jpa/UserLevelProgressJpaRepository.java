package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.UserLevelProgressEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLevelProgressJpaRepository extends JpaRepository<UserLevelProgressEntity, UUID> {

    List<UserLevelProgressEntity> findByOrderByTotalPointsDesc(Pageable pageable);
}
