package com.parkio.user.application.port;

import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecentParkingRepository {

    RecentParking save(RecentParking recent);

    Optional<RecentParking> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<RecentParking> findByUserProfileIdAndTarget(
            UUID userProfileId, RecentParkingTargetKind targetKind, UUID targetId);

    List<RecentParking> findAllByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    void deleteAllByUserProfileId(UUID userProfileId);
}
