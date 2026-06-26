package com.parkio.parking.application.geocoding;

/**
 * Signals that the geocoding provider could not be reached or refused the call
 * (timeout, circuit open, bulkhead full, outbound rate limit exceeded, transport
 * error). It is handled inside {@link GeocodingService} — which degrades to an
 * empty result set rather than surfacing an error — so it never reaches the
 * presentation layer. A degraded lookup is deliberately <em>not</em> cached, so a
 * transient outage does not poison the negative cache.
 */
public class GeocodingUnavailableException extends RuntimeException {

    public GeocodingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
