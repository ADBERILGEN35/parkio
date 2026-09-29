package com.parkio.user.application.port;

import com.parkio.user.domain.place.FavouriteDestination;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavouriteDestinationRepository {

    FavouriteDestination save(FavouriteDestination favourite);

    Optional<FavouriteDestination> findByIdAndUserProfileId(UUID id, UUID userProfileId);

    Optional<FavouriteDestination> findByUserProfileIdAndDuplicateKey(UUID userProfileId, String duplicateKey);

    List<FavouriteDestination> findAllByUserProfileId(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByIdAndUserProfileId(UUID id, UUID userProfileId);
}
