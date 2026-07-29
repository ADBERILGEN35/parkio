package com.parkio.parking.exposure;

import java.util.Objects;

/**
 * Privacy-safe, replayable request context for exposure evaluation.
 * Does not store exact viewer coordinates — uses rounded bands only.
 */
public record ExposureQueryContext(
        String searchType,
        String radiusBand,
        String limitBand,
        String locationBand,
        boolean authenticated) {

    public ExposureQueryContext {
        Objects.requireNonNull(searchType, "searchType");
        Objects.requireNonNull(radiusBand, "radiusBand");
        Objects.requireNonNull(limitBand, "limitBand");
        Objects.requireNonNull(locationBand, "locationBand");
    }
}
