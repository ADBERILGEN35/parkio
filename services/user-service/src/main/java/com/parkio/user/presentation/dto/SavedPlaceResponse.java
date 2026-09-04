package com.parkio.user.presentation.dto;

import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.SavedPlace;
import com.parkio.user.domain.place.SavedPlaceKind;
import java.time.Instant;
import java.util.UUID;

public record SavedPlaceResponse(
        UUID id,
        SavedPlaceKind kind,
        String label,
        double latitude,
        double longitude,
        PlaceDestinationSource source,
        PlaceIdentityResponse placeIdentity,
        String subtitle,
        Instant createdAt,
        Instant updatedAt) {

    public static SavedPlaceResponse from(SavedPlace place) {
        return new SavedPlaceResponse(
                place.id(),
                place.kind(),
                place.displayLabel(),
                place.latitude(),
                place.longitude(),
                place.source(),
                place.placeIdentityOptional().map(PlaceIdentityResponse::from).orElse(null),
                place.subtitle(),
                place.createdAt(),
                place.updatedAt());
    }

    public record PlaceIdentityResponse(String provider, String providerPlaceId, String canonicalKey) {
        public static PlaceIdentityResponse from(PlaceIdentity identity) {
            return new PlaceIdentityResponse(
                    identity.provider(), identity.providerPlaceId(), identity.canonicalKey());
        }
    }
}
