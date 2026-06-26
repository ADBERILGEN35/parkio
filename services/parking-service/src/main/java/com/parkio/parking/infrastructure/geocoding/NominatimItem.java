package com.parkio.parking.infrastructure.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Local, minimal view of a Nominatim {@code /search} (jsonv2) result item — only
 * the fields the mapping needs. Never shared across services. Unknown fields are
 * ignored (Nominatim returns many extra keys). {@code place_id} arrives as a JSON
 * number and is coerced to String.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimItem(
        String place_id,
        String display_name,
        String name,
        String lat,
        String lon,
        NominatimAddress address) {
}
