package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.RecentDestinationRepository;
import com.parkio.user.domain.place.RecentDestination;
import com.parkio.user.infrastructure.persistence.jpa.RecentDestinationJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.RecentPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RecentDestinationRepositoryAdapter implements RecentDestinationRepository {

    private final RecentDestinationJpaRepository jpa;

    public RecentDestinationRepositoryAdapter(RecentDestinationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RecentDestination save(RecentDestination recent) {
        return RecentPersistenceMapper.toDomain(jpa.save(RecentPersistenceMapper.toEntity(recent)));
    }

    @Override
    public Optional<RecentDestination> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
        return jpa.findByIdAndUserProfileId(id, userProfileId).map(RecentPersistenceMapper::toDomain);
    }

    @Override
    public Optional<RecentDestination> findByUserProfileIdAndDuplicateKey(
            UUID userProfileId, String duplicateKey) {
        return jpa.findByUserProfileIdAndDuplicateKey(userProfileId, duplicateKey)
                .map(RecentPersistenceMapper::toDomain);
    }

    @Override
    public List<RecentDestination> findAllByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId) {
        return jpa.findByUserProfileIdOrderByLastUsedAtDesc(userProfileId).stream()
                .map(RecentPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserProfileId(UUID userProfileId) {
        return jpa.countByUserProfileId(userProfileId);
    }

    @Override
    public void deleteByIdAndUserProfileId(UUID id, UUID userProfileId) {
        jpa.deleteByIdAndUserProfileId(id, userProfileId);
    }

    @Override
    public void deleteAllByUserProfileId(UUID userProfileId) {
        jpa.deleteByUserProfileId(userProfileId);
    }
}
