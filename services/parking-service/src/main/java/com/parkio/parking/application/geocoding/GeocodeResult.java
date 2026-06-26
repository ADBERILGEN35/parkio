package com.parkio.parking.application.geocoding;

/**
 * A single forward-geocoding candidate (free text → coordinates), shaped to match
 * the frontend's existing {@code GeocodeResult} contract exactly so the migration
 * behind the gateway is transparent to the SPA.
 *
 * <ul>
 *   <li>{@code id} — stable provider id, used for list keys.</li>
 *   <li>{@code displayName} — full human-readable address.</li>
 *   <li>{@code primary} — short label (place/street name or first address segment).</li>
 *   <li>{@code secondary} — e.g. "Konak, İzmir"; empty when unavailable.</li>
 * </ul>
 */
public record GeocodeResult(
        String id,
        String displayName,
        String primary,
        String secondary,
        double lat,
        double lng) {
}
