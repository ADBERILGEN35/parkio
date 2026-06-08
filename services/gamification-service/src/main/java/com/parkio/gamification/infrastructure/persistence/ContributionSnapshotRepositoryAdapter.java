package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.ContributionSnapshotRepository;
import com.parkio.gamification.domain.ContributionSnapshot;
import com.parkio.gamification.infrastructure.persistence.jpa.ContributionSnapshotJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link ContributionSnapshotRepository} port to Spring Data JPA. */
@Component
public class ContributionSnapshotRepositoryAdapter implements ContributionSnapshotRepository {

    private final ContributionSnapshotJpaRepository jpa;

    public ContributionSnapshotRepositoryAdapter(ContributionSnapshotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ContributionSnapshot save(ContributionSnapshot snapshot) {
        jpa.save(GamificationPersistenceMapper.toEntity(snapshot));
        return snapshot;
    }
}
