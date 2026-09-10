package com.parkio.user.infrastructure.persistence.mapper;

import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.infrastructure.persistence.entity.SavedPlaceEntity;

public final class SavedPlacePersistenceMapper {

    private SavedPlacePersistenceMapper() {
    }

    public static SavedPlace toDomain(SavedPlaceEntity entity) {
        PlaceIdentity identity = null;
        if (entity.getPlaceProvider() != null && entity.getPlaceProviderPlaceId() != null) {
            identity = PlaceIdentity.of(entity.getPlaceProvider(), entity.getPlaceProviderPlaceId());
        }
        return new SavedPlace(
                entity.getId(),
                entity.getUserProfileId(),
                entity.getKind(),
                entity.getLabel(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getSource(),
                identity,
                entity.getSubtitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    public static SavedPlaceEntity toEntity(SavedPlace place) {
        return new SavedPlaceEntity(
                place.id(),
                place.userProfileId(),
                place.kind(),
                place.label(),
                place.latitude(),
                place.longitude(),
                place.source(),
                place.placeIdentity() == null ? null : place.placeIdentity().provider(),
                place.placeIdentity() == null ? null : place.placeIdentity().providerPlaceId(),
                place.subtitle(),
                place.createdAt(),
                place.updatedAt(),
                place.version());
    }
}
