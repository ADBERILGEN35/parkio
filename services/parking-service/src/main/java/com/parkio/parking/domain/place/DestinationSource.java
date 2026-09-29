package com.parkio.parking.domain.place;

/**
 * Origin of a {@link Destination} intent.
 *
 * <p>Only values with a concrete construction path in the current SPA foundation
 * are defined here. User-owned origins ({@code SAVED_PLACE}, {@code FAVOURITE},
 * {@code RECENT}) arrive in later packages and must not be invented early.
 */
public enum DestinationSource {
    /** Bound from a geocoding search candidate. */
    GEOCODING,
    /** User confirmed a map pin as the trip target. */
    MAP_PIN,
    /** System-constructed destination (tests, internal tooling). */
    SYSTEM
}
