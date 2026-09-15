package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.domain.place.PlaceIdentity;

/**
 * Stable wire shape for a {@link Destination}. Additive and provider-neutral.
 *
 * <p>WP-SPA-02 does not expose a public Destination CRUD endpoint; this DTO
 * exists so later packages (recommendations, saved places) share one contract
 * and so serialization/compatibility can be tested now.
 */
public record DestinationResponse(
        String label,
        double latitude,
        double longitude,
        DestinationSource source,
        PlaceIdentityResponse placeIdentity,
        String subtitle) {

    public static DestinationResponse from(Destination destination) {
        PlaceIdentityResponse identity = destination.placeIdentityOptional()
                .map(PlaceIdentityResponse::from)
                .orElse(null);
        return new DestinationResponse(
                destination.label(),
                destination.latitude(),
                destination.longitude(),
                destination.source(),
                identity,
                destination.subtitle());
    }

    /** Nested optional identity; omitted/null when coordinates are the basis. */
    public record PlaceIdentityResponse(
            String provider,
            String providerPlaceId,
            String canonicalKey) {

        public static PlaceIdentityResponse from(PlaceIdentity identity) {
            return new PlaceIdentityResponse(
                    identity.provider(),
                    identity.providerPlaceId(),
                    identity.canonicalKey());
        }
    }
}
