package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.domain.place.PlaceIdentity;

/**
 * Nested destination on a recommendation request. Place identity is optional and
 * does not require a client-supplied canonicalKey.
 */
public record RecommendationDestinationRequest(
        String label,
        Double latitude,
        Double longitude,
        DestinationSource source,
        PlaceIdentityInput placeIdentity,
        String subtitle) {

    public Destination toDomain() {
        try {
            if (latitude == null || longitude == null) {
                throw new IllegalArgumentException("latitude and longitude are required");
            }
            DestinationSource resolvedSource = source != null ? source : DestinationSource.MAP_PIN;
            PlaceIdentity identity = null;
            if (placeIdentity != null) {
                identity = PlaceIdentity.of(placeIdentity.provider(), placeIdentity.providerPlaceId());
            }
            return Destination.of(label, latitude, longitude, resolvedSource, identity, subtitle);
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.INVALID_DESTINATION, ex.getMessage());
        }
    }

    public record PlaceIdentityInput(String provider, String providerPlaceId) {}
}
