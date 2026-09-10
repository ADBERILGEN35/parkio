package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import java.time.Instant;
import java.util.UUID;

public record FavouriteDestinationResponse(
        UUID id,
        String label,
        double latitude,
        double longitude,
        PlaceDestinationSource source,
        PlaceIdentityResponse placeIdentity,
        String subtitle,
        Instant createdAt,
        Instant updatedAt) {

    public static FavouriteDestinationResponse from(FavouriteDestination favourite) {
        return new FavouriteDestinationResponse(
                favourite.id(),
                favourite.label(),
                favourite.latitude(),
                favourite.longitude(),
                favourite.source(),
                favourite.placeIdentityOptional().map(PlaceIdentityResponse::from).orElse(null),
                favourite.subtitle(),
                favourite.createdAt(),
                favourite.updatedAt());
    }

    public record PlaceIdentityResponse(String provider, String providerPlaceId, String canonicalKey) {
        public static PlaceIdentityResponse from(PlaceIdentity identity) {
            return new PlaceIdentityResponse(
                    identity.provider(), identity.providerPlaceId(), identity.canonicalKey());
        }
    }
}
