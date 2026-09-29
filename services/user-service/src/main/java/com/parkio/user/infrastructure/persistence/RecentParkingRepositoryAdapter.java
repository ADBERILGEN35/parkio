package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.RecentParkingRepository;
import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import com.parkio.user.infrastructure.persistence.jpa.RecentParkingJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.RecentPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RecentParkingRepositoryAdapter implements RecentParkingRepository {

    private final RecentParkingJpaRepository jpa;

    public RecentParkingRepositoryAdapter(RecentParkingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RecentParking save(RecentParking recent) {
        return RecentPersistenceMapper.toDomain(jpa.save(RecentPersistenceMapper.toEntity(recent)));
    }

    @Override
    public Optional<RecentParking> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
        return jpa.findByIdAndUserProfileId(id, userProfileId).map(RecentPersistenceMapper::toDomain);
    }

    @Override
    public Optional<RecentParking> findByUserProfileIdAndTarget(
            UUID userProfileId, RecentParkingTargetKind targetKind, UUID targetId) {
        return jpa.findByUserProfileIdAndTargetKindAndTargetId(userProfileId, targetKind, targetId)
                .map(RecentPersistenceMapper::toDomain);
    }

    @Override
    public List<RecentParking> findAllByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId) {
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
