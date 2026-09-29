package com.parkio.user.application.port;

import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavouriteParkingRepository {

    FavouriteParking save(FavouriteParking favourite);

    Optional<FavouriteParking> findByUserProfileIdAndTarget(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId);

    List<FavouriteParking> findAllByUserProfileId(UUID userProfileId);

    long countByUserProfileId(UUID userProfileId);

    void deleteByUserProfileIdAndTarget(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId);

    List<FavouriteParking> findByUserProfileIdAndTargets(
            UUID userProfileId, FavouriteParkingTargetKind targetKind, Collection<UUID> targetIds);
}
