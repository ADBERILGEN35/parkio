package com.parkio.parking.application.port;

import com.parkio.parking.application.geocoding.GeocodeResult;
import java.util.List;

/**
 * Outbound port for forward geocoding (free text → coordinates). The application
 * depends only on this abstraction; the concrete provider (Nominatim today, an
 * SLA-backed provider later) is an infrastructure adapter, swappable by config
 * without touching the application or presentation layers.
 *
 * <p>Implementations are expected to be resilience-wrapped (timeout, circuit
 * breaker, bulkhead, outbound rate limiter) and to signal unreachability with
 * {@link com.parkio.parking.application.geocoding.GeocodingUnavailableException}.
 */
public interface GeocodingProvider {

    /**
     * Resolve {@code query} into at most {@code limit} candidate places.
     *
     * @param query a trimmed, validated (3–256 char) search string
     * @param limit validated result cap (1–10)
     * @return ordered candidates, possibly empty when the provider found nothing
     */
    List<GeocodeResult> search(String query, int limit);
}
