package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.ContributionSnapshotEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionSnapshotJpaRepository extends JpaRepository<ContributionSnapshotEntity, UUID> {
}
