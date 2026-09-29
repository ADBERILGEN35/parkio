package com.parkio.user.infrastructure.persistence.mapper;

import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.infrastructure.persistence.entity.FavouriteDestinationEntity;
import com.parkio.user.infrastructure.persistence.entity.FavouriteParkingEntity;

public final class FavouritePersistenceMapper {

    private FavouritePersistenceMapper() {
    }

    public static FavouriteParking toDomain(FavouriteParkingEntity entity) {
        return new FavouriteParking(
                entity.getId(),
                entity.getUserProfileId(),
                entity.getTargetKind(),
                entity.getTargetId(),
                entity.getCreatedAt(),
                entity.getVersion());
    }

    public static FavouriteParkingEntity toEntity(FavouriteParking favourite) {
        return new FavouriteParkingEntity(
                favourite.id(),
                favourite.userProfileId(),
                favourite.targetKind(),
                favourite.targetId(),
                favourite.createdAt(),
                favourite.version());
    }

    public static FavouriteDestination toDomain(FavouriteDestinationEntity entity) {
        PlaceIdentity identity = null;
        if (entity.getPlaceProvider() != null && entity.getPlaceProviderPlaceId() != null) {
            identity = PlaceIdentity.of(entity.getPlaceProvider(), entity.getPlaceProviderPlaceId());
        }
        return new FavouriteDestination(
                entity.getId(),
                entity.getUserProfileId(),
                entity.getLabel(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getSource(),
                identity,
                entity.getSubtitle(),
                entity.getDuplicateKey(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    public static FavouriteDestinationEntity toEntity(FavouriteDestination favourite) {
        return new FavouriteDestinationEntity(
                favourite.id(),
                favourite.userProfileId(),
                favourite.label(),
                favourite.latitude(),
                favourite.longitude(),
                favourite.source(),
                favourite.placeIdentity() == null ? null : favourite.placeIdentity().provider(),
                favourite.placeIdentity() == null ? null : favourite.placeIdentity().providerPlaceId(),
                favourite.subtitle(),
                favourite.duplicateKey(),
                favourite.createdAt(),
                favourite.updatedAt(),
                favourite.version());
    }
}
