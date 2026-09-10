package com.parkio.user.infrastructure.persistence.mapper;

import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.RecentDestination;
import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.infrastructure.persistence.entity.RecentDestinationEntity;
import com.parkio.user.infrastructure.persistence.entity.RecentParkingEntity;

public final class RecentPersistenceMapper {

    private RecentPersistenceMapper() {
    }

    public static RecentDestination toDomain(RecentDestinationEntity entity) {
        PlaceIdentity identity = null;
        if (entity.getPlaceProvider() != null && entity.getPlaceProviderPlaceId() != null) {
            identity = PlaceIdentity.of(entity.getPlaceProvider(), entity.getPlaceProviderPlaceId());
        }
        return new RecentDestination(
                entity.getId(),
                entity.getUserProfileId(),
                entity.getLabel(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getSource(),
                identity,
                entity.getSubtitle(),
                entity.getDuplicateKey(),
                entity.getFirstUsedAt(),
                entity.getLastUsedAt(),
                entity.getUseCount(),
                entity.getVersion());
    }

    public static RecentDestinationEntity toEntity(RecentDestination recent) {
        return new RecentDestinationEntity(
                recent.id(),
                recent.userProfileId(),
                recent.label(),
                recent.latitude(),
                recent.longitude(),
                recent.source(),
                recent.placeIdentity() == null ? null : recent.placeIdentity().provider(),
                recent.placeIdentity() == null ? null : recent.placeIdentity().providerPlaceId(),
                recent.subtitle(),
                recent.duplicateKey(),
                recent.firstUsedAt(),
                recent.lastUsedAt(),
                recent.useCount(),
                recent.version());
    }

    public static RecentParking toDomain(RecentParkingEntity entity) {
        return new RecentParking(
                entity.getId(),
                entity.getUserProfileId(),
                entity.getTargetKind(),
                entity.getTargetId(),
                entity.getFirstUsedAt(),
                entity.getLastUsedAt(),
                entity.getUseCount(),
                entity.getVersion());
    }

    public static RecentParkingEntity toEntity(RecentParking recent) {
        return new RecentParkingEntity(
                recent.id(),
                recent.userProfileId(),
                recent.targetKind(),
                recent.targetId(),
                recent.firstUsedAt(),
                recent.lastUsedAt(),
                recent.useCount(),
                recent.version());
    }
}
