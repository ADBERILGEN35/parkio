package com.parkio.user.application.port;

import com.parkio.user.domain.place.RecentDestination;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecentDestinationRepository {

    RecentDestination save(RecentDestination recent);

    Optional<RecentDestination> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<RecentDestination> findByUserProfileIdAndDuplicateKey(UUID userProfileId, String duplicateKey);

    List<RecentDestination> findAllByUserProfileIdOrderByLastUsedAtDesc(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);

    void deleteAllByUserProfileId(UUID userProfileId);
}
