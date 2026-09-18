package com.parkio.user.domain.place;

/**
 * How the place coordinates were established.
 *
 * <p>Mirrors parking-service DestinationSource values for shared contract
 * compatibility without creating a cross-service Java dependency.
 */
public enum PlaceDestinationSource {
    GEOCODING,
    MAP_PIN,
    SYSTEM
}
