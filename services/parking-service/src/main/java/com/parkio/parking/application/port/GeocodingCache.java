package com.parkio.parking.application.port;

import com.parkio.parking.application.geocoding.GeocodeResult;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for caching geocoding results. Implementations are best-effort:
 * a cache backend outage must degrade to a miss (never an error), so geocoding
 * keeps working — just without the cache.
 *
 * <p>An empty-but-present list is a valid cached value (the "negative cache" for
 * queries the provider returned nothing for), distinct from {@link Optional#empty()}
 * which means "not cached".
 */
public interface GeocodingCache {

    /** @return the cached results for {@code key}, or empty when absent/unavailable. */
    Optional<List<GeocodeResult>> get(String key);

    /**
     * Store {@code results} under {@code key}. The implementation chooses the TTL
     * by result emptiness (short negative TTL for empty, long positive TTL otherwise).
     */
    void put(String key, List<GeocodeResult> results);
}
