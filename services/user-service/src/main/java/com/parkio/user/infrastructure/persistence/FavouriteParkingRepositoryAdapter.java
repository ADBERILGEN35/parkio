package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.FavouriteDestinationRepository;
import com.parkio.user.application.port.FavouriteParkingRepository;
import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import com.parkio.user.infrastructure.persistence.jpa.FavouriteDestinationJpaRepository;
import com.parkio.user.infrastructure.persistence.jpa.FavouriteParkingJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.FavouritePersistenceMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class FavouriteParkingRepositoryAdapter implements FavouriteParkingRepository {

    private final FavouriteParkingJpaRepository jpa;

    public FavouriteParkingRepositoryAdapter(FavouriteParkingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public FavouriteParking save(FavouriteParking favourite) {
        return FavouritePersistenceMapper.toDomain(jpa.save(FavouritePersistenceMapper.toEntity(favourite)));
    }

    @Override
    public Optional<FavouriteParking> findByUserProfileIdAndTarget(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId) {
        return jpa.findByUserProfileIdAndTargetKindAndTargetId(userProfileId, targetKind, targetId)
                .map(FavouritePersistenceMapper::toDomain);
    }

    @Override
    public List<FavouriteParking> findAllByUserProfileId(UUID userProfileId) {
        return jpa.findByUserProfileIdOrderByCreatedAtDesc(userProfileId).stream()
                .map(FavouritePersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countByUserProfileId(UUID userProfileId) {
        return jpa.countByUserProfileId(userProfileId);
    }

    @Override
    public void deleteByUserProfileIdAndTarget(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId) {
        jpa.deleteByUserProfileIdAndTargetKindAndTargetId(userProfileId, targetKind, targetId);
    }

    @Override
    public List<FavouriteParking> findByUserProfileIdAndTargets(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, Collection<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByUserProfileIdAndTargetKindAndTargetIdIn(userProfileId, targetKind, targetIds).stream()
                .map(FavouritePersistenceMapper::toDomain)
                .toList();
    }
}
