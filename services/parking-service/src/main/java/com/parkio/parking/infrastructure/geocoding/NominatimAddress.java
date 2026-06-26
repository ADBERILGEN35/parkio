package com.parkio.parking.infrastructure.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Local view of Nominatim's {@code address} object — only the components used to
 * derive the primary/secondary labels. Snake-case names match the JSON keys.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimAddress(
        String road,
        String pedestrian,
        String suburb,
        String quarter,
        String city_district,
        String district,
        String county,
        String town,
        String city,
        String village,
        String province,
        String state) {

    /** No-args used as a null-safe default in the mapper. */
    public NominatimAddress() {
        this(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
