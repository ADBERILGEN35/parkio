package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.domain.place.RecentParkingTargetKind;
import com.parkio.user.infrastructure.persistence.entity.RecentParkingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecentParkingJpaRepository extends JpaRepository<RecentParkingEntity, UUID> {

    Optional<RecentParkingEntity> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<RecentParkingEntity> findByUserProfileIdAndTargetKindAndTargetId(
            UUID userProfileId, RecentParkingTargetKind targetKind, UUID targetId);

    List<RecentParkingEntity> findByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    void deleteByUserProfileId(UUID userProfileId);
}
