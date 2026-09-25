package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import com.parkio.user.infrastructure.persistence.entity.FavouriteParkingEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavouriteParkingJpaRepository extends JpaRepository<FavouriteParkingEntity, UUID> {

    Optional<FavouriteParkingEntity> findByUserProfileIdAndTargetKindAndTargetId(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId);

    List<FavouriteParkingEntity> findByUserProfileIdOrderByCreatedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByUserProfileId(UUID userProfileId);

    void deleteByUserProfileIdAndTargetKindAndTargetId(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId);

    List<FavouriteParkingEntity> findByUserProfileIdAndTargetKindAndTargetIdIn(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, Collection<UUID> targetIds);
}
